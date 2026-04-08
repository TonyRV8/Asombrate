package com.example.asombrate

// --- Capa lógica: modelo de datos de asientos desacoplado del render ---

enum class SeatState { SUN, SHADE, PARTIAL, NEUTRAL, SELECTED }

enum class VehicleType(
    val rows: Int,
    val cols: Int,
    val label: String,
    val aisleAfterCol: Int?
) {
    CAR(rows = 2, cols = 2, label = "Auto", aisleAfterCol = null),
    BUS(rows = 10, cols = 4, label = "Autobús", aisleAfterCol = 2)
}

data class Seat(
    val id: String,          // "1A", "3C", etc.
    val row: Int,            // 1-based
    val col: Int,            // 1-based
    val state: SeatState,
    val exposure: Double = 0.0,  // 0..1 proporción del trayecto con sol
    val score: Double = 0.0      // score numérico para ranking (menor = mejor)
)

data class SeatPlan(
    val vehicleType: VehicleType,
    val seats: List<Seat>
) {
    fun seatAt(row: Int, col: Int): Seat? =
        seats.firstOrNull { it.row == row && it.col == col }
}

object SeatIds {
    /** Genera un identificador humano: fila + letra de columna ("1A", "10D"). */
    fun build(row: Int, col: Int): String = "$row${('A' + col - 1)}"

    /** Formato legible para recomendación textual. */
    fun readable(seat: Seat): String = "Fila ${seat.row}, Columna ${('A' + seat.col - 1)}"
}

/**
 * Adaptador TEMPORAL desde la recomendación actual (lado + porcentaje de sombra)
 * hacia un mapa de asientos. Cuando exista la lógica solar por asiento, este
 * objeto debería reemplazarse por un calculador real sin tocar la capa visual.
 */
object SeatPlanAdapter {

    /**
     * @param vehicleType tipo de vehículo a renderizar.
     * @param shadySide "IZQUIERDO" o "DERECHO" (o null si no se pudo determinar).
     * @param shadePercent porcentaje del trayecto con sombra del lado recomendado (0..100).
     */
    fun fromSidePercent(
        vehicleType: VehicleType,
        shadySide: String?,
        shadePercent: Int
    ): SeatPlan {
        val seats = ArrayList<Seat>(vehicleType.rows * vehicleType.cols)
        val midCol = vehicleType.cols / 2  // cols <= midCol => lado izquierdo

        for (row in 1..vehicleType.rows) {
            for (col in 1..vehicleType.cols) {
                val state = computeState(col, midCol, shadySide, shadePercent)
                seats.add(
                    Seat(
                        id = SeatIds.build(row, col),
                        row = row,
                        col = col,
                        state = state
                    )
                )
            }
        }
        return SeatPlan(vehicleType, seats)
    }

    private fun computeState(
        col: Int,
        midCol: Int,
        shadySide: String?,
        shadePercent: Int
    ): SeatState {
        if (shadySide == null) return SeatState.NEUTRAL
        val isLeft = col <= midCol
        val isShady = (shadySide == "IZQUIERDO" && isLeft) ||
                (shadySide == "DERECHO" && !isLeft)

        return if (isShady) {
            when {
                shadePercent >= 70 -> SeatState.SHADE
                shadePercent >= 40 -> SeatState.PARTIAL
                else -> SeatState.NEUTRAL
            }
        } else {
            when {
                shadePercent >= 70 -> SeatState.SUN
                shadePercent >= 40 -> SeatState.PARTIAL
                else -> SeatState.NEUTRAL
            }
        }
    }
}
