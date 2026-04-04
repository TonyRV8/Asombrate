package com.example.asombrate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

class ShadowViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<ShadowState>(ShadowState.Idle)
    val uiState = _uiState.asStateFlow()

    private var selectedCalendar: Calendar = Calendar.getInstance()

    fun updateDepartureTime(calendar: Calendar) {
        selectedCalendar = calendar
    }

    private suspend fun getCoordinates(text: String, apiKey: String): String? {
        val trimmed = text.trim().trim(',')
        val clean = trimmed.replace(" ", "")
        
        val parts = clean.split(",").filter { it.isNotBlank() }
        if (parts.size == 2 && parts.all { it.toDoubleOrNull() != null }) {
            return "${parts[0]},${parts[1]}"
        }
        
        return try {
            val response = RetrofitClient.instance.geocode(text, apiKey)
            if (response.features.isNotEmpty()) {
                val coords = response.features[0].geometry.coordinates
                "${coords[0]},${coords[1]}" // lng,lat
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun calculateShadow(origin: String, destination: String) {
        if (origin.isBlank() || destination.isBlank()) {
            _uiState.value = ShadowState.Error("Falta origen o destino", "")
            return
        }

        viewModelScope.launch {
            _uiState.value = ShadowState.Loading
            var debugLog = "DEBUG LOG:\n"
            try {
                // API KEY de OpenRouteService (leída desde local.properties via BuildConfig)
                val apiKey = BuildConfig.ORS_API_KEY

                // 1. Geocoding
                val startCoords = getCoordinates(origin, apiKey)
                if (startCoords == null) {
                    _uiState.value = ShadowState.Error("No se encontró Origen: $origin", debugLog)
                    return@launch
                }
                debugLog += "Origen: $startCoords\n"

                val endCoords = getCoordinates(destination, apiKey)
                if (endCoords == null) {
                    _uiState.value = ShadowState.Error("No se encontró Destino: $destination", debugLog)
                    return@launch
                }
                debugLog += "Destino: $endCoords\n"

                // 2. Directions POST con RouteRequest
                debugLog += "Pidiendo ruta a ORS (POST)...\n"

                val startParts = startCoords.split(",")
                val endParts = endCoords.split(",")
                val routeRequest = RouteRequest(
                    coordinates = listOf(
                        listOf(startParts[0].toDouble(), startParts[1].toDouble()),
                        listOf(endParts[0].toDouble(), endParts[1].toDouble())
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

                // 3. Procesamiento de la respuesta (mapeada a DirectionsResponse)
                val routes = response.routes
                if (!routes.isNullOrEmpty()) {
                    val route = routes[0]
                    val geometry = route.geometry
                    
                    if (geometry.isNullOrBlank()) {
                        _uiState.value = ShadowState.Error("Respuesta sin geometría", debugLog)
                        return@launch
                    }

                    val points = ShadowUtils.decodePolyline(geometry)
                    debugLog += "Puntos en ruta: ${points.size}\n"
                    
                    var leftCount = 0
                    var rightCount = 0
                    var highSunCount = 0

                    for (i in 0 until points.size - 1) {
                        val start = points[i]
                        val end = points[i+1]
                        val bearing = ShadowUtils.calculateBearing(start, end)
                        val side = ShadowUtils.calculateShadowSide(bearing, start.lat, start.lng, selectedCalendar)

                        when (side) {
                            "IZQUIERDA" -> leftCount++
                            "DERECHA" -> rightCount++
                            "TECHO" -> highSunCount++
                        }
                    }

                    val finalResults = mutableListOf<ShadowResult>()
                    val total = leftCount + rightCount + highSunCount
                    
                    if (total > 0) {
                        val side = if (rightCount >= leftCount) "DERECHO" else "IZQUIERDO"
                        val percent = if (rightCount >= leftCount) (rightCount * 100 / total) else (leftCount * 100 / total)
                        
                        finalResults.add(ShadowResult(
                            "Siéntate del lado $side",
                            "Tendrás sombra el $percent% del trayecto.",
                            Icons.Default.Check,
                            Color(0xFF4CAF50)
                        ))
                    }

                    _uiState.value = ShadowState.Success(finalResults, debugLog)
                } else {
                    _uiState.value = ShadowState.Error("No se encontró ruta", debugLog)
                }
            } catch (e: Exception) {
                _uiState.value = ShadowState.Error("Error crítico", debugLog + "Ex: ${e.message}")
            }
        }
    }
}
