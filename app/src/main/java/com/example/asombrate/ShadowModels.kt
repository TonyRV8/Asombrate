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

sealed class ShadowState {
    object Idle : ShadowState()
    object Loading : ShadowState()
    data class Success(
        val results: List<ShadowResult>, 
        val debugInfo: String
    ) : ShadowState()
    data class Error(
        val message: String, 
        val debugInfo: String? = null
    ) : ShadowState()
}
