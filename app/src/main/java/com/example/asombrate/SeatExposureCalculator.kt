package com.example.asombrate

import java.util.Calendar

// --- Capa lógica Fase 2: exposición solar por asiento ---

enum class ShadeSide { LEFT, RIGHT, OVERHEAD, NONE }

data class RouteSegmentSolar(
    val distanceMeters: Double,
    val shadeSide: ShadeSide
)

data class RouteSolarProfile(
    val segments: List<RouteSegmentSolar>,
    val totalDistanceMeters: Double,
    val validDistanceMeters: Double
)

data class SeatExposureResult(
    val plan: SeatPlan,
    val recommendedSeatId: String?,
    val alternatives: List<String>,
    val metrics: String
)

/**
 * Calculador real de exposición solar por asiento.
 *
 * Paso 1: computeProfile recorre la polyline decodificada y produce, por cada
 *         segmento, distancia real en metros + lado de sombra (reutilizando
 *         ShadowUtils.calculateShadowSide).
 * Paso 2: buildPlan pondera por distancia y aplica factores distintos para
 *         ventana vs interior, clasifica cada asiento y ranking el mejor.
 * fallbackPlan: usa el adaptador temporal cuando no hay suficientes datos.
 */
object SeatExposureCalculator {

    private const val WINDOW_SUN_FACTOR = 1.0
    private const val INTERIOR_SUN_FACTOR = 0.55
    private const val OVERHEAD_FACTOR = 0.40

    private const val SUN_THRESHOLD = 0.65
    private const val PARTIAL_THRESHOLD = 0.30

    /** Bucket para considerar dos scores "similares" en el desempate. */
    private const val TIE_BUCKET = 0.02

    fun computeProfile(
        points: List<Location>,
        calendar: Calendar
    ): RouteSolarProfile {
        if (points.size < 2) {
            return RouteSolarProfile(emptyList(), 0.0, 0.0)
        }
        val segs = ArrayList<RouteSegmentSolar>(points.size - 1)
        var total = 0.0
        var valid = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val dist = ShadowUtils.distanceMeters(a, b)
            if (dist <= 0.0) continue
            val bearing = ShadowUtils.calculateBearing(a, b)
            val sideStr = ShadowUtils.calculateShadowSide(bearing, a.lat, a.lng, calendar)
            val shade = when (sideStr) {
                "IZQUIERDA" -> ShadeSide.LEFT
                "DERECHA" -> ShadeSide.RIGHT
                "TECHO" -> ShadeSide.OVERHEAD
                else -> ShadeSide.NONE
            }
            segs.add(RouteSegmentSolar(dist, shade))
            total += dist
            if (shade != ShadeSide.NONE) valid += dist
        }
        return RouteSolarProfile(
            segments = segs,
            totalDistanceMeters = total,
            validDistanceMeters = valid
        )
    }

    fun buildPlan(
        profile: RouteSolarProfile,
        vehicleType: VehicleType
    ): SeatExposureResult {
        val denom = profile.totalDistanceMeters
        if (profile.segments.isEmpty() || denom <= 0.0 || profile.validDistanceMeters <= 0.0) {
            return neutralResult(vehicleType, "Datos solares insuficientes")
        }

        val seats = ArrayList<Seat>(vehicleType.rows * vehicleType.cols)
        for (row in 1..vehicleType.rows) {
            for (col in 1..vehicleType.cols) {
                val isLeft = col <= vehicleType.cols / 2
                val isWindow = col == 1 || col == vehicleType.cols
                var weighted = 0.0
                for (seg in profile.segments) {
                    weighted += seg.distanceMeters * sunFactor(seg.shadeSide, isLeft, isWindow)
                }
                val exposure = (weighted / denom).coerceIn(0.0, 1.0)
                val state = when {
                    exposure >= SUN_THRESHOLD -> SeatState.SUN
                    exposure >= PARTIAL_THRESHOLD -> SeatState.PARTIAL
                    else -> SeatState.SHADE
                }
                seats.add(
                    Seat(
                        id = SeatIds.build(row, col),
                        row = row,
                        col = col,
                        state = state,
                        exposure = exposure,
                        score = exposure
                    )
                )
            }
        }

        val ranked = rankSeats(seats, vehicleType)
        val recommended = ranked.firstOrNull()
        val alternatives = ranked.drop(1).take(3).map { it.id }

        val metrics = buildString {
            append("segmentos=${profile.segments.size} ")
            append("distTotal=${"%.0f".format(denom)}m ")
            append("distValida=${"%.0f".format(profile.validDistanceMeters)}m\n")
            ranked.take(5).forEach {
                append("${it.id} sol=${"%.2f".format(it.exposure)} ${it.state}\n")
            }
        }

        return SeatExposureResult(
            plan = SeatPlan(vehicleType, seats),
            recommendedSeatId = recommended?.id,
            alternatives = alternatives,
            metrics = metrics
        )
    }

    fun fallbackPlan(
        vehicleType: VehicleType,
        shadySide: String?,
        shadePercent: Int
    ): SeatExposureResult {
        val plan = SeatPlanAdapter.fromSidePercent(vehicleType, shadySide, shadePercent)
        val ranked = plan.seats.sortedWith(
            compareBy<Seat>(
                { stateRank(it.state) },
                { it.row },
                { windowRank(it.col, vehicleType) }
            )
        )
        val rec = ranked.firstOrNull { it.state != SeatState.NEUTRAL } ?: ranked.firstOrNull()
        val alts = ranked.filter { it.id != rec?.id }.take(3).map { it.id }
        return SeatExposureResult(
            plan = plan,
            recommendedSeatId = rec?.id,
            alternatives = alts,
            metrics = "fallback lado=$shadySide pct=$shadePercent"
        )
    }

    // --- helpers ---

    private fun sunFactor(shade: ShadeSide, isLeft: Boolean, isWindow: Boolean): Double {
        return when (shade) {
            ShadeSide.NONE -> 0.0
            ShadeSide.OVERHEAD -> OVERHEAD_FACTOR
            ShadeSide.LEFT -> {
                // Sombra en izquierda: derecha expuesta
                if (isLeft) 0.0
                else if (isWindow) WINDOW_SUN_FACTOR else INTERIOR_SUN_FACTOR
            }
            ShadeSide.RIGHT -> {
                // Sombra en derecha: izquierda expuesta
                if (!isLeft) 0.0
                else if (isWindow) WINDOW_SUN_FACTOR else INTERIOR_SUN_FACTOR
            }
        }
    }

    private fun rankSeats(seats: List<Seat>, vehicleType: VehicleType): List<Seat> {
        return seats.sortedWith(
            compareBy<Seat> { (it.score / TIE_BUCKET).toInt() }   // primario: score (bucket)
                .thenBy { it.row }                                // desempate 1: frente
                .thenBy { windowRank(it.col, vehicleType) }       // desempate 2: interior > ventana
                .thenBy { it.score }                              // desempate final: score exacto
        )
    }

    private fun stateRank(state: SeatState): Int = when (state) {
        SeatState.SHADE -> 0
        SeatState.PARTIAL -> 1
        SeatState.NEUTRAL -> 2
        SeatState.SUN -> 3
        SeatState.SELECTED -> 4
    }

    private fun windowRank(col: Int, vehicleType: VehicleType): Int {
        val isWindow = col == 1 || col == vehicleType.cols
        return if (isWindow) 1 else 0
    }

    private fun neutralResult(vehicleType: VehicleType, reason: String): SeatExposureResult {
        val seats = ArrayList<Seat>(vehicleType.rows * vehicleType.cols)
        for (row in 1..vehicleType.rows) {
            for (col in 1..vehicleType.cols) {
                seats.add(
                    Seat(
                        id = SeatIds.build(row, col),
                        row = row,
                        col = col,
                        state = SeatState.NEUTRAL,
                        exposure = 0.0,
                        score = 0.0
                    )
                )
            }
        }
        return SeatExposureResult(
            plan = SeatPlan(vehicleType, seats),
            recommendedSeatId = null,
            alternatives = emptyList(),
            metrics = reason
        )
    }
}
