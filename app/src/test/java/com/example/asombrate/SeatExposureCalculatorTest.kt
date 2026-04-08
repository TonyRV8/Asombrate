package com.example.asombrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatExposureCalculatorTest {

    private fun profile(vararg segs: Pair<Double, ShadeSide>): RouteSolarProfile {
        val list = segs.map { RouteSegmentSolar(it.first, it.second) }
        val total = list.sumOf { it.distanceMeters }
        val valid = list.filter { it.shadeSide != ShadeSide.NONE }.sumOf { it.distanceMeters }
        return RouteSolarProfile(list, total, valid)
    }

    @Test
    fun `dominancia lado derecho sombrea asientos derechos y expone izquierdos`() {
        // Sombra en derecha durante todo el trayecto -> C y D deben quedar en SHADE
        val p = profile(1000.0 to ShadeSide.RIGHT, 1000.0 to ShadeSide.RIGHT)

        val result = SeatExposureCalculator.buildPlan(p, VehicleType.BUS)

        val rightSeats = result.plan.seats.filter { it.col == 3 || it.col == 4 }
        assertTrue(
            "Todos los asientos C/D deberían estar en SHADE",
            rightSeats.all { it.state == SeatState.SHADE }
        )
        val leftWindow = result.plan.seats.filter { it.col == 1 }
        assertTrue(
            "Todos los asientos A (ventana izquierda) deberían estar en SUN",
            leftWindow.all { it.state == SeatState.SUN }
        )
        val rec = result.plan.seats.first { it.id == result.recommendedSeatId }
        assertTrue("Recomendado debe estar en lado derecho", rec.col >= 3)
    }

    @Test
    fun `dominancia lado izquierdo sombrea asientos izquierdos`() {
        val p = profile(2000.0 to ShadeSide.LEFT)

        val result = SeatExposureCalculator.buildPlan(p, VehicleType.BUS)

        val leftSeats = result.plan.seats.filter { it.col == 1 || it.col == 2 }
        assertTrue(leftSeats.all { it.state == SeatState.SHADE })

        val rightWindow = result.plan.seats.filter { it.col == 4 }
        assertTrue(rightWindow.all { it.state == SeatState.SUN })

        val rec = result.plan.seats.first { it.id == result.recommendedSeatId }
        assertTrue("Recomendado debe estar en lado izquierdo", rec.col <= 2)
    }

    @Test
    fun `mezcla parcial ubica ventanas e interiores entre PARTIAL`() {
        // 60% del trayecto con sombra derecha (izq expuesto)
        // 40% del trayecto con sombra izquierda (der expuesto)
        val p = profile(
            1200.0 to ShadeSide.RIGHT,
            800.0 to ShadeSide.LEFT
        )

        val result = SeatExposureCalculator.buildPlan(p, VehicleType.BUS)

        // Col 1 (ventana izq): 1200/2000 = 0.60 -> PARTIAL
        val a1 = result.plan.seats.first { it.id == "1A" }
        assertEquals(SeatState.PARTIAL, a1.state)
        // Col 2 (interior izq): 1200 * 0.55 / 2000 = 0.33 -> PARTIAL
        val b1 = result.plan.seats.first { it.id == "1B" }
        assertEquals(SeatState.PARTIAL, b1.state)
        // Col 4 (ventana der): 800/2000 = 0.40 -> PARTIAL
        val d1 = result.plan.seats.first { it.id == "1D" }
        assertEquals(SeatState.PARTIAL, d1.state)
        // Col 3 (interior der): 800 * 0.55 / 2000 = 0.22 -> SHADE
        val c1 = result.plan.seats.first { it.id == "1C" }
        assertEquals(SeatState.SHADE, c1.state)

        // Recomendado debe ser el de menor exposición (C, interior derecha) en fila 1
        assertEquals("1C", result.recommendedSeatId)
    }

    @Test
    fun `datos insuficientes produce plan neutral sin recomendacion`() {
        val p = RouteSolarProfile(emptyList(), 0.0, 0.0)

        val result = SeatExposureCalculator.buildPlan(p, VehicleType.BUS)

        assertNull(result.recommendedSeatId)
        assertTrue(result.alternatives.isEmpty())
        assertTrue(result.plan.seats.all { it.state == SeatState.NEUTRAL })
    }

    @Test
    fun `empate en score desempata por fila frontal e interior sobre ventana`() {
        // Toda la ruta con sombra derecha -> todos los asientos C y D
        // tienen score 0.0. El desempate debe escoger fila 1, columna interior (C).
        val p = profile(1500.0 to ShadeSide.RIGHT)

        val result = SeatExposureCalculator.buildPlan(p, VehicleType.BUS)

        assertEquals("1C", result.recommendedSeatId)
        // Top 3 alternativas deben ser asientos del lado sombreado, fila 1 o 2
        val alts = result.alternatives
        assertEquals(3, alts.size)
        assertNotNull(alts.firstOrNull { it.endsWith("D") || it.endsWith("C") })
    }

    @Test
    fun `vehiculo auto 2x2 recomienda asiento del lado sombreado`() {
        val p = profile(1000.0 to ShadeSide.LEFT)

        val result = SeatExposureCalculator.buildPlan(p, VehicleType.CAR)

        // Col 1 (izq, ventana) sombreada -> SHADE, score 0
        // Col 2 (der, ventana) expuesta  -> SUN, score 1
        val left = result.plan.seats.filter { it.col == 1 }
        assertTrue(left.all { it.state == SeatState.SHADE })
        val right = result.plan.seats.filter { it.col == 2 }
        assertTrue(right.all { it.state == SeatState.SUN })

        // Recomendado: fila 1, col 1 = "1A"
        assertEquals("1A", result.recommendedSeatId)
    }

    @Test
    fun `fallbackPlan usa adaptador y entrega recomendacion cuando hay lado`() {
        val result = SeatExposureCalculator.fallbackPlan(
            vehicleType = VehicleType.BUS,
            shadySide = "DERECHO",
            shadePercent = 80
        )
        assertNotNull(result.recommendedSeatId)
        val rec = result.plan.seats.first { it.id == result.recommendedSeatId }
        assertTrue("fallback debe recomendar asiento lado derecho", rec.col >= 3)
    }
}
