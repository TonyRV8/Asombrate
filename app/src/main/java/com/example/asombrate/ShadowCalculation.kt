package com.example.asombrate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.Color
import java.util.Calendar

/**
 * Capa pura de cálculo de la recomendación completa.
 *
 * Toma una ruta (puntos) y un Calendar y devuelve TODO lo que la UI necesita
 * pintar: lado sombreado, porcentaje, plan por cada vehículo con nivel de
 * confianza y explicación derivada. No toca red ni Compose ni Android.
 *
 * Esto centraliza la "fuente única de verdad" (Fase 1) y habilita pruebas
 * unitarias puras de la lógica de presentación (Fase 5).
 */
object ShadowCalculation {

    /** Distancia válida mínima (m) para considerar el cálculo robusto. */
    private const val MIN_DISTANCE_FOR_HIGH = 2000.0
    private const val MIN_DISTANCE_FOR_MEDIUM = 500.0

    /** Ratio cobertura (valid/total) mínimo para confianza alta/media. */
    private const val COVERAGE_HIGH = 0.70
    private const val COVERAGE_MEDIUM = 0.40

    private val vehiclesToPrecompute = listOf(VehicleType.BUS, VehicleType.CAR)

    /**
     * Construye el Success listo para pintar a partir de la ruta decodificada.
     *
     * @param points polyline decodificada.
     * @param calendar hora del viaje.
     * @param debugHeader string ya formateado con contexto previo de debug.
     */
    fun buildSuccess(
        points: List<Location>,
        calendar: Calendar,
        debugHeader: String = ""
    ): ShadowState.Success {
        val profile = SeatExposureCalculator.computeProfile(points, calendar)
        return buildSuccessFromProfile(profile, debugHeader)
    }

    fun buildSuccessFromProfile(
        profile: RouteSolarProfile,
        debugHeader: String = ""
    ): ShadowState.Success {
        val sideAndPercent = dominantSide(profile)
        val shadySide = sideAndPercent.first
        val shadePercent = sideAndPercent.second

        val recommendations = LinkedHashMap<VehicleType, VehicleRecommendation>()
        for (v in vehiclesToPrecompute) {
            val seatResult =
                if (profile.validDistanceMeters > 0.0 && profile.totalDistanceMeters > 0.0) {
                    SeatExposureCalculator.buildPlan(profile, v)
                } else {
                    SeatExposureCalculator.fallbackPlan(v, shadySide, shadePercent)
                }
            val isFallback = profile.validDistanceMeters <= 0.0 || profile.totalDistanceMeters <= 0.0
            val explanation = buildExplanation(profile, seatResult, isFallback)
            recommendations[v] = VehicleRecommendation(v, seatResult, explanation)
        }

        val results = buildTopLevelResults(shadySide, shadePercent, recommendations[VehicleType.BUS])

        val debugLog = buildString {
            append(debugHeader)
            append("Segmentos: ${profile.segments.size} ")
            append("distTotal=${"%.0f".format(profile.totalDistanceMeters)}m ")
            append("distValida=${"%.0f".format(profile.validDistanceMeters)}m\n")
            recommendations[VehicleType.BUS]?.seatResult?.metrics?.let { append(it) }
        }

        return ShadowState.Success(
            results = results,
            debugInfo = debugLog,
            shadySide = shadySide,
            shadePercent = shadePercent,
            routeProfile = profile,
            recommendations = recommendations
        )
    }

    /** Devuelve ("IZQUIERDO"/"DERECHO"/null, porcentaje 0..100). */
    fun dominantSide(profile: RouteSolarProfile): Pair<String?, Int> {
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
        if (totalShade <= 0.0) return null to 0
        val side = if (rightShade >= leftShade) "DERECHO" else "IZQUIERDO"
        val dominant = if (side == "DERECHO") rightShade else leftShade
        val pct = ((dominant * 100.0) / totalShade).toInt()
        return side to pct
    }

    /**
     * Deriva el nivel de confianza a partir del perfil solar:
     * - Cobertura (validDistance/totalDistance).
     * - Distancia útil absoluta.
     */
    fun confidenceFor(profile: RouteSolarProfile): RecommendationConfidence {
        val total = profile.totalDistanceMeters
        val valid = profile.validDistanceMeters
        if (total <= 0.0 || valid <= 0.0) return RecommendationConfidence.NONE
        val coverage = valid / total

        return when {
            coverage >= COVERAGE_HIGH && valid >= MIN_DISTANCE_FOR_HIGH ->
                RecommendationConfidence.HIGH
            coverage >= COVERAGE_MEDIUM && valid >= MIN_DISTANCE_FOR_MEDIUM ->
                RecommendationConfidence.MEDIUM
            else -> RecommendationConfidence.LOW
        }
    }

    private fun buildExplanation(
        profile: RouteSolarProfile,
        seatResult: SeatExposureResult,
        isFallback: Boolean
    ): RecommendationExplanation {
        val recId = seatResult.recommendedSeatId
        val recSeat = seatResult.plan.seats.firstOrNull { it.id == recId }
        val exposurePct = recSeat?.let { (it.exposure * 100).toInt() } ?: 0
        val total = profile.totalDistanceMeters
        val coverageRatio =
            if (total > 0.0) (profile.validDistanceMeters / total).coerceIn(0.0, 1.0) else 0.0
        val confidence =
            if (isFallback) RecommendationConfidence.LOW
            else confidenceFor(profile)

        return RecommendationExplanation(
            recommendedSeatId = recId,
            recommendedSeatReadable = recSeat?.let { SeatIds.readable(it) },
            exposurePercent = exposurePct,
            coveragePercent = (coverageRatio * 100).toInt(),
            confidence = confidence,
            isFallback = isFallback
        )
    }

    private fun buildTopLevelResults(
        shadySide: String?,
        shadePercent: Int,
        busRec: VehicleRecommendation?
    ): List<ShadowResult> {
        if (shadySide == null) return emptyList()
        val recSeat =
            busRec?.seatResult?.plan?.seats?.firstOrNull { it.id == busRec.seatResult.recommendedSeatId }
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
        return listOf(ShadowResult(title, desc, Icons.Default.Check, Color(0xFF4CAF50)))
    }
}
