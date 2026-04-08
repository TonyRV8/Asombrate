package com.example.asombrate

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.google.gson.annotations.SerializedName

// Modelo para los resultados de sombra
data class ShadowResult(
    val title: String, 
    val description: String, 
    val icon: ImageVector, 
    val color: Color
)

// Modelos para Geocoding de OpenRouteService
data class GeocodeResponse(
    @SerializedName("features") val features: List<GeocodeFeature>
)

data class GeocodeFeature(
    @SerializedName("geometry") val geometry: GeocodeGeometry,
    @SerializedName("properties") val properties: GeocodeProperties
)

data class GeocodeGeometry(
    @SerializedName("coordinates") val coordinates: List<Double>
)

data class GeocodeProperties(
    @SerializedName("label") val label: String
)

// Body para el POST de OpenRouteService Directions
data class RouteRequest(
    val coordinates: List<List<Double>>
)

// Data Classes estrictas para OpenRouteService Directions
data class DirectionsResponse(
    @SerializedName("routes") val routes: List<RouteItem>?
)

data class RouteItem(
    @SerializedName("geometry") val geometry: String?
)

data class Location(
    val lat: Double,
    val lng: Double
)

// Modelo para sugerencias del autocompletado
data class LocationSuggestion(
    val label: String,
    val lat: Double,
    val lng: Double
)

// Estado de un campo de ubicación con autocompletado + mapa interactivo
data class LocationFieldState(
    val query: String = "",
    val suggestions: List<LocationSuggestion> = emptyList(),
    val confirmed: LocationSuggestion? = null,
    val isSearching: Boolean = false,
    val mapLat: Double = 19.4326,   // Default: CDMX centro
    val mapLng: Double = -99.1332,
    val isReverseGeocoding: Boolean = false,
    val flyToVersion: Int = 0       // Se incrementa para indicar animación programática
)

sealed class ShadowState {
    object Idle : ShadowState()
    object Loading : ShadowState()
    data class Success(
        val results: List<ShadowResult>,
        val debugInfo: String,
        val shadySide: String? = null,   // "IZQUIERDO" / "DERECHO" / null
        val shadePercent: Int = 0,       // 0..100
        val routeProfile: RouteSolarProfile? = null,
        val defaultSeatResult: SeatExposureResult? = null
    ) : ShadowState()
    data class Error(
        val message: String, 
        val debugInfo: String? = null
    ) : ShadowState()
}
