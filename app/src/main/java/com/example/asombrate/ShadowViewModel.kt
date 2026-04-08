package com.example.asombrate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.Color
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

    // Flows internos para debounce de texto
    private val _originQuery = MutableStateFlow("")
    private val _destinationQuery = MutableStateFlow("")

    // Flows internos para debounce de movimiento del mapa
    private val _originMapCenter = MutableSharedFlow<LatLng>(extraBufferCapacity = 1)
    private val _destinationMapCenter = MutableSharedFlow<LatLng>(extraBufferCapacity = 1)

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
                .collect { center -> reverseGeocode(center, _originState) }
        }
        // Debounce reverse geocode - Destino (500ms)
        viewModelScope.launch {
            _destinationMapCenter
                .debounce(500)
                .collect { center -> reverseGeocode(center, _destinationState) }
        }
    }

    fun updateDepartureTime(calendar: Calendar) {
        selectedCalendar = calendar
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
        val s = _originState.value
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
        state.update { it.copy(isSearching = true) }
        try {
            val response = RetrofitClient.instance.geocode(query, apiKey)
            val suggestions = response.features.map { feature ->
                LocationSuggestion(
                    label = feature.properties.label,
                    lat = feature.geometry.coordinates[1],
                    lng = feature.geometry.coordinates[0]
                )
            }
            state.update { it.copy(suggestions = suggestions, isSearching = false) }
        } catch (_: Exception) {
            state.update { it.copy(suggestions = emptyList(), isSearching = false) }
        }
    }

    private suspend fun reverseGeocode(
        center: LatLng,
        state: MutableStateFlow<LocationFieldState>
    ) {
        state.update { it.copy(isReverseGeocoding = true) }
        try {
            val response = RetrofitClient.instance.reverseGeocode(
                lat = center.lat,
                lon = center.lng,
                apiKey = apiKey
            )
            val label = response.features.firstOrNull()?.properties?.label
                ?: "%.5f, %.5f".format(center.lat, center.lng)
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
                    RetrofitClient.instance.getDirections(
                        apiKey = apiKey,
                        request = routeRequest
                    )
                } catch (e: Exception) {
                    _uiState.value = ShadowState.Error("Error de red", debugLog + "Error: ${e.message}")
                    return@launch
                }

                val routes = response.routes
                if (!routes.isNullOrEmpty()) {
                    val geometry = routes[0].geometry

                    if (geometry.isNullOrBlank()) {
                        _uiState.value = ShadowState.Error("Respuesta sin geometría", debugLog)
                        return@launch
                    }

                    val points = ShadowUtils.decodePolyline(geometry)
                    debugLog += "Puntos en ruta: ${points.size}\n"

                    // Fase 2: profile por segmento ponderado por distancia
                    val profile = SeatExposureCalculator.computeProfile(points, selectedCalendar)
                    debugLog += "Segmentos: ${profile.segments.size} " +
                        "distTotal=${"%.0f".format(profile.totalDistanceMeters)}m " +
                        "distValida=${"%.0f".format(profile.validDistanceMeters)}m\n"

                    // Lado/porcentaje dominantes a partir del profile (ponderado por distancia)
                    var leftShade = 0.0
                    var rightShade = 0.0
                    var overhead = 0.0
                    for (seg in profile.segments) {
                        when (seg.shadeSide) {
                            ShadeSide.LEFT -> leftShade += seg.distanceMeters
                            ShadeSide.RIGHT -> rightShade += seg.distanceMeters
                            ShadeSide.OVERHEAD -> overhead += seg.distanceMeters
                            ShadeSide.NONE -> Unit
                        }
                    }
                    val totalShade = leftShade + rightShade + overhead
                    var shadySide: String? = null
                    var shadePercent = 0
                    if (totalShade > 0.0) {
                        shadySide = if (rightShade >= leftShade) "DERECHO" else "IZQUIERDO"
                        val dominant =
                            if (shadySide == "DERECHO") rightShade else leftShade
                        shadePercent = ((dominant * 100.0) / totalShade).toInt()
                    }

                    // Plan por defecto (BUS) con cálculo real, o null si no hay datos
                    val defaultSeatResult: SeatExposureResult? =
                        if (profile.validDistanceMeters > 0.0) {
                            SeatExposureCalculator.buildPlan(profile, VehicleType.BUS)
                        } else null

                    val finalResults = mutableListOf<ShadowResult>()
                    if (shadySide != null) {
                        val recId = defaultSeatResult?.recommendedSeatId
                        val recSeat = defaultSeatResult?.plan?.seats?.firstOrNull { it.id == recId }
                        val title = if (recSeat != null) {
                            "Siéntate en ${SeatIds.readable(recSeat)}"
                        } else {
                            "Siéntate del lado $shadySide"
                        }
                        val desc = if (recSeat != null) {
                            val pct = (recSeat.exposure * 100).toInt()
                            "Asiento con menor exposición al sol (${pct}%). " +
                                "Lado $shadySide sombreado en $shadePercent% del trayecto."
                        } else {
                            "Tendrás sombra el $shadePercent% del trayecto."
                        }
                        finalResults.add(
                            ShadowResult(title, desc, Icons.Default.Check, Color(0xFF4CAF50))
                        )
                    }

                    defaultSeatResult?.let { debugLog += it.metrics }

                    _uiState.value = ShadowState.Success(
                        results = finalResults,
                        debugInfo = debugLog,
                        shadySide = shadySide,
                        shadePercent = shadePercent,
                        routeProfile = profile,
                        defaultSeatResult = defaultSeatResult
                    )
                } else {
                    _uiState.value = ShadowState.Error("No se encontró ruta", debugLog)
                }
            } catch (e: Exception) {
                _uiState.value = ShadowState.Error("Error crítico", debugLog + "Ex: ${e.message}")
            }
        }
    }
}
