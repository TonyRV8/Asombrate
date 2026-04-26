package com.example.asombrate

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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
class ShadowViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_SELECTED_VEHICLE = "selected_vehicle"
    }

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(getApplication<Application>())
    }

    private val _uiState = MutableStateFlow<ShadowState>(ShadowState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _serviceMode = MutableStateFlow(ServiceMode.NORMAL)
    val serviceMode = _serviceMode.asStateFlow()

    private val _originState = MutableStateFlow(LocationFieldState())
    val originState = _originState.asStateFlow()

    private val _destinationState = MutableStateFlow(LocationFieldState())
    val destinationState = _destinationState.asStateFlow()

    /** Vehículo seleccionado por UI — vive en VM para que Success sea SSOT. */
    private val _selectedVehicle = MutableStateFlow(
        VehicleType.entries.firstOrNull {
            it.name == savedStateHandle.get<String>(KEY_SELECTED_VEHICLE)
        } ?: VehicleType.BUS
    )
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
    private val directionsCache = TtlCache<String, List<Location>>(
        ttlMillis = 2 * 60_000L,
        maxEntries = 32
    )

    // Último punto consultado por reverse para evitar micro-movimientos.
    private var lastOriginReverseAt: LatLng? = null
    private var lastDestinationReverseAt: LatLng? = null
    private var lastSuccessfulRoutePoints: List<Location>? = null

    private var selectedCalendar: Calendar = Calendar.getInstance()

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
        savedStateHandle[KEY_SELECTED_VEHICLE] = type.name
    }

    // --- Ubicación Actual ---

    @SuppressLint("MissingPermission")
    fun useCurrentLocation(isOrigin: Boolean) {
        val stateFlow = if (isOrigin) _originState else _destinationState
        stateFlow.update {
            it.copy(
                isReverseGeocoding = true,
                statusMessage = null,
                statusMessageIsError = false
            )
        }
        
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            CancellationTokenSource().token
        ).addOnSuccessListener { location ->
            if (location != null) {
                val lat = location.latitude
                val lng = location.longitude
                stateFlow.update {
                    it.copy(
                        mapLat = lat,
                        mapLng = lng,
                        flyToVersion = it.flyToVersion + 1
                    )
                }
                viewModelScope.launch {
                    reverseGeocode(LatLng(lat, lng), stateFlow)
                    // Auto-confirmar si es ubicación actual
                    if (isOrigin) onOriginMapConfirmed() else onDestinationMapConfirmed()
                }
            } else {
                stateFlow.update { it.copy(isReverseGeocoding = false) }
                setFieldStatus(
                    stateFlow,
                    UiText(R.string.error_location_unavailable),
                    isError = true
                )
            }
        }.addOnFailureListener {
            stateFlow.update { it.copy(isReverseGeocoding = false) }
            setFieldStatus(
                stateFlow,
                UiText(R.string.error_location_unavailable),
                isError = true
            )
        }
    }

    // --- Búsqueda por texto ---

    fun onOriginQueryChanged(text: String) {
        _originState.update {
            it.copy(
                query = text,
                confirmed = null,
                statusMessage = null,
                statusMessageIsError = false
            )
        }
        _originQuery.value = text
    }

    fun onDestinationQueryChanged(text: String) {
        _destinationState.update {
            it.copy(
                query = text,
                confirmed = null,
                statusMessage = null,
                statusMessageIsError = false
            )
        }
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
                flyToVersion = it.flyToVersion + 1,
                statusMessage = null,
                statusMessageIsError = false
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
                flyToVersion = it.flyToVersion + 1,
                statusMessage = null,
                statusMessageIsError = false
            )
        }
        lastDestinationReverseAt = LatLng(suggestion.lat, suggestion.lng)
    }

    // --- Movimiento manual del mapa → reverse geocode ---

    fun onOriginMapMoved(lat: Double, lng: Double) {
        _originState.update {
            it.copy(
                mapLat = lat,
                mapLng = lng,
                confirmed = null,
                isReverseGeocoding = true,
                statusMessage = null,
                statusMessageIsError = false
            )
        }
        _originMapCenter.tryEmit(LatLng(lat, lng))
    }

    fun onDestinationMapMoved(lat: Double, lng: Double) {
        _destinationState.update {
            it.copy(
                mapLat = lat,
                mapLng = lng,
                confirmed = null,
                isReverseGeocoding = true,
                statusMessage = null,
                statusMessageIsError = false
            )
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
                suggestions = emptyList(),
                statusMessage = null,
                statusMessageIsError = false
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
                suggestions = emptyList(),
                statusMessage = null,
                statusMessageIsError = false
            )
        }
    }

    private fun setFieldStatus(
        state: MutableStateFlow<LocationFieldState>,
        message: UiText?,
        isError: Boolean
    ) {
        state.update {
            it.copy(
                statusMessage = message,
                statusMessageIsError = isError
            )
        }
    }

    private fun updateServiceModeFromError(error: Throwable) {
        val usageStateHeader = extractUsageStateHeader(error)
        val classified = NetworkErrorClassifier.classify(error, usageStateHeader)
        _serviceMode.value = parseUsageStateHeader(usageStateHeader) ?: classified.serviceMode
    }

    // --- Funciones internas ---

    private suspend fun searchSuggestions(
        query: String,
        state: MutableStateFlow<LocationFieldState>
    ) {
        if (query.isBlank()) {
            state.update {
                it.copy(
                    suggestions = emptyList(),
                    isSearching = false,
                    statusMessage = null,
                    statusMessageIsError = false
                )
            }
            return
        }
        // Fase 2: cache por query normalizada
        val key = query.trim().lowercase()
        geocodeCache.get(key)?.let { cached ->
            state.update {
                it.copy(
                    suggestions = cached,
                    isSearching = false,
                    statusMessage = null,
                    statusMessageIsError = false
                )
            }
            return
        }
        state.update { it.copy(isSearching = true) }
        try {
            val response = retryingCall {
                RetrofitClient.instance.geocode(
                    GeocodeSearchRequest(text = query)
                )
            }
            val suggestions = response.features.orEmpty().mapNotNull { feature ->
                val coordinates = feature.geometry?.coordinates
                val lat = coordinates?.getOrNull(1)
                val lng = coordinates?.getOrNull(0)
                val label = feature.properties?.label?.takeIf { it.isNotBlank() }

                if (label == null || lat == null || lng == null) {
                    null
                } else {
                    LocationSuggestion(
                        label = label,
                        lat = lat,
                        lng = lng
                    )
                }
            }
            geocodeCache.put(key, suggestions)
            state.update {
                it.copy(
                    suggestions = suggestions,
                    isSearching = false,
                    statusMessage = null,
                    statusMessageIsError = false
                )
            }
        } catch (e: Exception) {
            updateServiceModeFromError(e)
            val err = NetworkErrorClassifier.classify(e, extractUsageStateHeader(e))
            state.update {
                it.copy(
                    suggestions = emptyList(),
                    isSearching = false,
                    statusMessage = err.userMessage,
                    statusMessageIsError = true
                )
            }
        }
    }

    private suspend fun reverseGeocode(
        center: LatLng,
        state: MutableStateFlow<LocationFieldState>
    ) {
        // Fase 2: cache por lat/lng redondeado (bucket ~10m)
        val key = "%.4f,%.4f".format(center.lat, center.lng)
        reverseGeocodeCache.get(key)?.let { cached ->
            state.update {
                it.copy(
                    query = cached,
                    isReverseGeocoding = false,
                    statusMessage = null,
                    statusMessageIsError = false
                )
            }
            return
        }
        state.update { it.copy(isReverseGeocoding = true) }
        try {
            val response = retryingCall {
                RetrofitClient.instance.reverseGeocode(
                    ReverseGeocodeRequest(
                        lat = center.lat,
                        lon = center.lng
                    )
                )
            }
            val label = response.features.orEmpty().firstOrNull()?.properties?.label
                ?: "%.5f, %.5f".format(center.lat, center.lng)
            reverseGeocodeCache.put(key, label)
            state.update {
                it.copy(
                    query = label,
                    isReverseGeocoding = false,
                    statusMessage = null,
                    statusMessageIsError = false
                )
            }
        } catch (e: Exception) {
            updateServiceModeFromError(e)
            state.update {
                it.copy(
                    query = "%.5f, %.5f".format(center.lat, center.lng),
                    isReverseGeocoding = false,
                    statusMessage = UiText(R.string.error_reverse_geocode_temporarily_unavailable),
                    statusMessageIsError = true
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
                UiText(R.string.error_confirm_before_calc),
                ""
            )
            return
        }

        // Validación: si no hay sol a esa hora
        if (!ShadowUtils.isSunUp(originConfirmed.lat, originConfirmed.lng, selectedCalendar)) {
            _uiState.value = ShadowState.Error(
                message = UiText(R.string.error_no_sun_at_night),
                debugInfo = "",
                isNightError = true
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = ShadowState.Loading
            var debugLog = "DEBUG LOG:\n"
            try {
                debugLog += "Origen y destino confirmados.\n"
                val routeKey = buildDirectionsCacheKey(originConfirmed, destinationConfirmed, selectedCalendar)
                val cachedPoints = directionsCache.get(routeKey)
                val points = if (cachedPoints != null) {
                    debugLog += "Ruta cache: HIT\n"
                    cachedPoints
                } else run {
                    debugLog += "Ruta cache: MISS\n"
                    debugLog += "Pidiendo ruta al backend (POST)...\n"

                    val routeRequest = RouteRequest(
                        coordinates = listOf(
                            listOf(originConfirmed.lng, originConfirmed.lat),
                            listOf(destinationConfirmed.lng, destinationConfirmed.lat)
                        )
                    )

                    val response = try {
                        retryingCall {
                            RetrofitClient.instance.getDirections(routeRequest)
                        }
                    } catch (e: Exception) {
                        val usageStateHeader = extractUsageStateHeader(e)
                        val err = NetworkErrorClassifier.classify(e, usageStateHeader)
                        _serviceMode.value = parseUsageStateHeader(usageStateHeader) ?: err.serviceMode

                        val fallback = lastSuccessfulRoutePoints
                        if (fallback != null) {
                            debugLog += "Directions fallo. Reusando ultima ruta valida.\n"
                            return@run fallback
                        }

                        _uiState.value = ShadowState.Error(
                            err.userMessage,
                            debugLog + err.debugDetail
                        )
                        return@launch
                    }

                    _serviceMode.value =
                        parseUsageStateHeader(RetrofitClient.consumeLastUsageStateHeader()) ?: ServiceMode.NORMAL

                    val routes = response.routes
                    if (routes.isNullOrEmpty()) {
                        _uiState.value = ShadowState.Error(UiText(R.string.error_no_route), debugLog)
                        return@launch
                    }

                    val geometry = routes[0].geometry
                    if (geometry.isNullOrBlank()) {
                        _uiState.value = ShadowState.Error(UiText(R.string.error_no_geometry), debugLog)
                        return@launch
                    }

                    val decoded = ShadowUtils.decodePolyline(geometry)
                    directionsCache.put(routeKey, decoded)
                    decoded
                }
                lastSuccessfulRoutePoints = points
                debugLog += "Puntos en ruta: ${points.size}\n"

                _uiState.value = ShadowCalculation.buildSuccess(
                    points = points,
                    calendar = selectedCalendar,
                    debugHeader = debugLog
                )
            } catch (e: Exception) {
                val usageStateHeader = extractUsageStateHeader(e)
                val err = NetworkErrorClassifier.classify(e, usageStateHeader)
                _serviceMode.value = parseUsageStateHeader(usageStateHeader) ?: err.serviceMode
                _uiState.value = ShadowState.Error(
                    err.userMessage,
                    debugLog + "Ex: " + err.debugDetail
                )
            }
        }
    }

    private fun extractUsageStateHeader(error: Throwable): String? {
        return (error as? retrofit2.HttpException)
            ?.response()
            ?.headers()
            ?.get("X-Usage-State")
    }

    private fun buildDirectionsCacheKey(
        origin: LocationSuggestion,
        destination: LocationSuggestion,
        calendar: Calendar
    ): String {
        return String.format(
            "%.5f,%.5f|%.5f,%.5f|%02d:%02d",
            origin.lat,
            origin.lng,
            destination.lat,
            destination.lng,
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
    }
}
