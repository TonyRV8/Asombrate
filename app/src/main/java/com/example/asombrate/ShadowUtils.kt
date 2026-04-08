package com.example.asombrate

import org.shredzone.commons.suncalc.SunPosition
import java.util.Calendar
import kotlin.math.*

object ShadowUtils {

    /**
     * Decodifica una polyline de OpenRouteService.
     */
    fun decodePolyline(encoded: String): List<Location> {
        val poly = ArrayList<Location>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(Location(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
        }
        return poly

    }

    /**
     * Distancia en metros entre dos puntos usando haversine.
     */
    fun distanceMeters(a: Location, b: Location): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2.0)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * Calcula el rumbo (bearing) entre dos puntos.
     */
    fun calculateBearing(start: Location, end: Location): Double {
        val lat1 = Math.toRadians(start.lat)
        val lon1 = Math.toRadians(start.lng)
        val lat2 = Math.toRadians(end.lat)
        val lon2 = Math.toRadians(end.lng)

        val y = sin(lon2 - lon1) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon2 - lon1)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }

    /**
     * Algoritmo de sombra compatible con SunCalc 3.x
     */
    fun calculateShadowSide(bearing: Double, lat: Double, lng: Double, calendar: Calendar): String? {
        val position = SunPosition.compute()
            .on(calendar)
            .at(lat, lng)
            .execute()

        val azimuth = position.azimuth 
        // Normalizar azimut a 0=Norte (SunCalc 3.x usa 180=Sur, 0=Sur? No, 3.x usa 0=Sur, -90=Este, 90=Oeste)
        // Normalización para que 0 = Norte
        val normalizedAzimuth = (azimuth + 180) % 360

        val elevation = position.altitude 

        if (elevation > 75.0) return "TECHO"
        if (elevation < 0) return null

        val diff = (normalizedAzimuth - bearing + 360) % 360

        return when {
            diff in 0.0..180.0 -> "DERECHA"
            else -> "IZQUIERDA"
        }
    }
}
