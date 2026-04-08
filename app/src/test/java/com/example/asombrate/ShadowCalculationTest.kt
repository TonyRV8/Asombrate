package com.example.asombrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowCalculationTest {

    private fun profile(vararg segs: Pair<Double, ShadeSide>): RouteSolarProfile {
        val list = segs.map { RouteSegmentSolar(it.first, it.second) }
        val total = list.sumOf { it.distanceMeters }
        val valid = list.filter { it.shadeSide != ShadeSide.NONE }.sumOf { it.distanceMeters }
        return RouteSolarProfile(list, total, valid)
    }

    @Test
    fun `dominantSide con mas derecha gana DERECHO`() {
        val p = profile(1000.0 to ShadeSide.RIGHT, 500.0 to ShadeSide.LEFT)
        val (side, pct) = ShadowCalculation.dominantSide(p)
        assertEquals("DERECHO", side)
        // 1000 / (1000 + 500) = 66%
        assertTrue(pct in 60..70)
    }

    @Test
    fun `dominantSide sin datos devuelve null`() {
        val p = RouteSolarProfile(emptyList(), 0.0, 0.0)
        val (side, pct) = ShadowCalculation.dominantSide(p)
        assertNull(side)
        assertEquals(0, pct)
    }

    @Test
    fun `confidenceFor alta con cobertura alta y distancia suficiente`() {
        val p = profile(3000.0 to ShadeSide.RIGHT)
        assertEquals(RecommendationConfidence.HIGH, ShadowCalculation.confidenceFor(p))
    }

    @Test
    fun `confidenceFor media cuando cobertura buena pero distancia corta`() {
        val p = profile(800.0 to ShadeSide.RIGHT)
        assertEquals(RecommendationConfidence.MEDIUM, ShadowCalculation.confidenceFor(p))
    }

    @Test
    fun `confidenceFor baja cuando cobertura insuficiente`() {
        // 100m válidos de 1000m totales -> 10% cobertura -> LOW
        val p = RouteSolarProfile(
            listOf(
                RouteSegmentSolar(100.0, ShadeSide.RIGHT),
                RouteSegmentSolar(900.0, ShadeSide.NONE)
            ),
            totalDistanceMeters = 1000.0,
            validDistanceMeters = 100.0
        )
        assertEquals(RecommendationConfidence.LOW, ShadowCalculation.confidenceFor(p))
    }

    @Test
    fun `confidenceFor NONE cuando no hay datos validos`() {
        val p = RouteSolarProfile(emptyList(), 0.0, 0.0)
        assertEquals(RecommendationConfidence.NONE, ShadowCalculation.confidenceFor(p))
    }

    @Test
    fun `buildSuccessFromProfile con datos validos produce Success listo para render`() {
        val p = profile(3000.0 to ShadeSide.RIGHT)
        val success = ShadowCalculation.buildSuccessFromProfile(p)
        assertEquals("DERECHO", success.shadySide)
        assertTrue(success.shadePercent > 0)
        // Debe precomputar plan BUS y CAR
        assertNotNull(success.recommendations[VehicleType.BUS])
        assertNotNull(success.recommendations[VehicleType.CAR])
        val busExp = success.recommendations[VehicleType.BUS]!!.explanation
        assertEquals(RecommendationConfidence.HIGH, busExp.confidence)
        assertNotNull(busExp.recommendedSeatId)
        assertTrue(busExp.exposurePercent == 0) // asiento sombreado, 0%
        assertEquals(false, busExp.isFallback)
    }

    @Test
    fun `buildSuccessFromProfile sin datos usa fallback y marca isFallback`() {
        val p = RouteSolarProfile(emptyList(), 0.0, 0.0)
        val success = ShadowCalculation.buildSuccessFromProfile(p)
        assertNull(success.shadySide)
        val busExp = success.recommendations[VehicleType.BUS]!!.explanation
        assertTrue(busExp.isFallback)
        assertEquals(RecommendationConfidence.NONE, busExp.confidence)
    }
}
