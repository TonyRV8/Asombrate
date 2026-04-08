package com.example.asombrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class LatLng(val lat: Double, val lng: Double)

@OptIn(FlowPreview::class)
class ShadowViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<ShadowState>(ShadowState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _originState = MutableStateFlow(LocationFieldState())
    val originState = _originState.asStateFlow()

    private val _destinationState = MutableStateFlow(LocationFieldState())
    val destinationState = _destinationState.asStateFlow()

    /** Vehículo seleccionado por UI — vive en VM para que Success sea SSOT. */
    private val _selectedVehicle = MutableStateFlow(VehicleType.BUS)
    val selectedVehicle = _selectedVehicle.asStateFlow()

    // Flows internos para debounce de texto
    private val _originQuery = MutableStateFlow("")
    private val _destinationQuery = MutableStateFlow("")

    // Flows internos para debounce de movimiento del mapa
    private val _originMapCenter = MutableSharedFlow<LatLng>(extraBufferCapacity = 1)
    private val _destinationMapCenter = MutableSharedFlow<LatLng>(extraBufferCapacity = 1)

    // Fase 2: cache TTL para reducir llamadas redundantes
    private val geocodeCache = TtlCache<String, List<LocationSuggestion>>(ttlMillis = 5 * 60_000L)
    private val reverseGeocodeCache = TtlCache<String, String>(ttlMillis = 5 * 60_000L)

    // Último punto consultado por reverse para evitar micro-movimientos.
    private var lastOriginReverseAt: LatLng? = null
    private var lastDestinationReverseAt: LatLng? = null

    private var selectedCalendar: Calendar = Calendar.getInstance()
    private val apiKey = BuildConfig.ORS_API_KEY

    init {
        // Debounce búsqueda por texto - Origen
        viewModelScope.launch {
            _originQuery
                .debounce(400)
                .distinctUntilChanged()
                .filter { it.length >= 3 }
                .collect { query -> searchSuggestions(query, _originState) }
        }
        // Debounce búsqueda por texto - Destino
        viewModelScope.launch {
            _destinationQuery
                .debounce(400)
                .distinctUntilChanged()
                .filter { it.length >= 3 }
                .collect { query -> searchSuggestions(query, _destinationState) }
        }
        // Debounce reverse geocode - Origen (500ms)
        viewModelScope.launch {
            _originMapCenter
                .debounce(500)
                .collect { center ->
                    if (MapMoveThreshold.shouldTrigger(lastOriginReverseAt, center)) {
                        lastOriginReverseAt = center
                        reverseGeocode(center, _originState)
                    } else {
                        // Movimiento despreciable: limpia estado de loading sin consumir API.
                        _originState.update { it.copy(isReverseGeocoding = false) }
                    }
                }
        }
        // Debounce reverse geocode - Destino (500ms)
        viewModelScope.launch {
            _destinationMapCenter
                .debounce(500)
                .collect { center ->
                    if (MapMoveThreshold.shouldTrigger(lastDestinationReverseAt, center)) {
                        lastDestinationReverseAt = center
                        reverseGeocode(center, _destinationState)
                    } else {
                        _destinationState.update { it.copy(isReverseGeocoding = false) }
                    }
                }
        }
    }

    fun updateDepartureTime(calendar: Calendar) {
        selectedCalendar = calendar
    }

    fun selectVehicle(type: VehicleType) {
        _selectedVehicle.value = type
    }

    // --- Búsqueda por texto ---

    fun onOriginQueryChanged(text: String) {
        _originState.update { it.copy(query = text, confirmed = null) }
        _originQuery.value = text
    }

    fun onDestinationQueryChanged(text: String) {
        _destinationState.update { it.copy(query = text, confirmed = null) }
        _destinationQuery.value = text
    }

    // --- Selección de sugerencia de texto → vuela el mapa ---

    fun onOriginSelected(suggestion: LocationSuggestion) {
        _originState.update {
            it.copy(
                query = suggestion.label,
                confirmed = suggestion,
                suggestions = emptyList(),
                mapLat = suggestion.lat,
                mapLng = suggestion.lng,
                flyToVersion = it.flyToVersion + 1
            )
        }
        lastOriginReverseAt = LatLng(suggestion.lat, suggestion.lng)
    }

    fun onDestinationSelected(suggestion: LocationSuggestion) {
        _destinationState.update {
            it.copy(
                query = suggestion.label,
                confirmed = suggestion,
                suggestions = emptyList(),
                mapLat = suggestion.lat,
                mapLng = suggestion.lng,
                flyToVersion = it.flyToVersion + 1
            )
        }
        lastDestinationReverseAt = LatLng(suggestion.lat, suggestion.lng)
    }

    // --- Movimiento manual del mapa → reverse geocode ---

    fun onOriginMapMoved(lat: Double, lng: Double) {
        _originState.update {
            it.copy(mapLat = lat, mapLng = lng, confirmed = null, isReverseGeocoding = true)
        }
        _originMapCenter.tryEmit(LatLng(lat, lng))
    }

    fun onDestinationMapMoved(lat: Double, lng: Double) {
        _destinationState.update {
            it.copy(mapLat = lat, mapLng = lng, confirmed = null, isReverseGeocoding = true)
        }
        _destinationMapCenter.tryEmit(LatLng(lat, lng))
    }

    // --- Confirmar ubicación desde el mapa ---

    fun onOriginMapConfirmed() {
        _originState.update {
            it.copy(
                confirmed = LocationSuggestion(
                    label = it.query.ifBlank { "%.5f, %.5f".format(it.mapLat, it.mapLng) },
                    lat = it.mapLat,
                    lng = it.mapLng
                ),
                suggestions = emptyList()
            )
        }
    }

    fun onDestinationMapConfirmed() {
        _destinationState.update {
            it.copy(
                confirmed = LocationSuggestion(
                    label = it.query.ifBlank { "%.5f, %.5f".format(it.mapLat, it.mapLng) },
                    lat = it.mapLat,
                    lng = it.mapLng
                ),
                suggestions = emptyList()
            )
        }
    }

    // --- Funciones internas ---

    private suspend fun searchSuggestions(
        query: String,
        state: MutableStateFlow<LocationFieldState>
    ) {
        // Fase 2: cache por query normalizada
        val key = query.trim().lowercase()
        geocodeCache.get(key)?.let { cached ->
            state.update { it.copy(suggestions = cached, isSearching = false) }
            return
        }
        state.update { it.copy(isSearching = true) }
        try {
            val response = retryingCall { RetrofitClient.instance.geocode(query, apiKey) }
            val suggestions = response.features.map { feature ->
                LocationSuggestion(
                    label = feature.properties.label,
                    lat = feature.geometry.coordinates[1],
                    lng = feature.geometry.coordinates[0]
                )
            }
            geocodeCache.put(key, suggestions)
            state.update { it.copy(suggestions = suggestions, isSearching = false) }
        } catch (_: Exception) {
            state.update { it.copy(suggestions = emptyList(), isSearching = false) }
        }
    }

    private suspend fun reverseGeocode(
        center: LatLng,
        state: MutableStateFlow<LocationFieldState>
    ) {
        // Fase 2: cache por lat/lng redondeado (bucket ~10m)
        val key = "%.4f,%.4f".format(center.lat, center.lng)
        reverseGeocodeCache.get(key)?.let { cached ->
            state.update { it.copy(query = cached, isReverseGeocoding = false) }
            return
        }
        state.update { it.copy(isReverseGeocoding = true) }
        try {
            val response = retryingCall {
                RetrofitClient.instance.reverseGeocode(
                    lat = center.lat,
                    lon = center.lng,
                    apiKey = apiKey
                )
            }
            val label = response.features.firstOrNull()?.properties?.label
                ?: "%.5f, %.5f".format(center.lat, center.lng)
            reverseGeocodeCache.put(key, label)
            state.update {
                it.copy(query = label, isReverseGeocoding = false)
            }
        } catch (_: Exception) {
            state.update {
                it.copy(
                    query = "%.5f, %.5f".format(center.lat, center.lng),
                    isReverseGeocoding = false
                )
            }
        }
    }

    // --- Cálculo de sombra ---

    fun calculateShadow() {
        val originConfirmed = _originState.value.confirmed
        val destinationConfirmed = _destinationState.value.confirmed

        if (originConfirmed == null || destinationConfirmed == null) {
            _uiState.value = ShadowState.Error(
                "Confirma origen y destino antes de calcular", ""
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = ShadowState.Loading
            var debugLog = "DEBUG LOG:\n"
            try {
                debugLog += "Origen: ${originConfirmed.lng},${originConfirmed.lat}\n"
                debugLog += "Destino: ${destinationConfirmed.lng},${destinationConfirmed.lat}\n"
                debugLog += "Pidiendo ruta a ORS (POST)...\n"

                val routeRequest = RouteRequest(
                    coordinates = listOf(
                        listOf(originConfirmed.lng, originConfirmed.lat),
                        listOf(destinationConfirmed.lng, destinationConfirmed.lat)
                    )
                )

                val response = try {
                    retryingCall {
                        RetrofitClient.instance.getDirections(
                            apiKey = apiKey,
                            request = routeRequest
                        )
                    }
                } catch (e: Exception) {
                    val err = NetworkErrorClassifier.classify(e)
                    _uiState.value = ShadowState.Error(
                        err.userMessage,
                        debugLog + err.debugDetail
                    )
                    return@launch
                }

                val routes = response.routes
                if (routes.isNullOrEmpty()) {
                    _uiState.value = ShadowState.Error("No se encontró ruta", debugLog)
                    return@launch
                }

                val geometry = routes[0].geometry
                if (geometry.isNullOrBlank()) {
                    _uiState.value = ShadowState.Error("Respuesta sin geometría", debugLog)
                    return@launch
                }

                val points = ShadowUtils.decodePolyline(geometry)
                debugLog += "Puntos en ruta: ${points.size}\n"

                _uiState.value = ShadowCalculation.buildSuccess(
                    points = points,
                    calendar = selectedCalendar,
                    debugHeader = debugLog
                )
            } catch (e: Exception) {
                val err = NetworkErrorClassifier.classify(e)
                _uiState.value = ShadowState.Error(
                    err.userMessage,
                    debugLog + "Ex: " + err.debugDetail
                )
            }
        }
    }
}
