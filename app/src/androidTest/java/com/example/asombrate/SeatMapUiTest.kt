package com.example.asombrate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.asombrate.ui.theme.AsombrateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeatMapUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clickEnAsiento_actualizaSeleccion() {
        val plan = SeatPlanAdapter.fromSidePercent(
            vehicleType = VehicleType.BUS,
            shadySide = "DERECHO",
            shadePercent = 80
        )
        var selectedSeatId: String? by mutableStateOf(null)

        composeRule.setContent {
            AsombrateTheme {
                SeatMap(
                    plan = plan,
                    selectedSeatId = selectedSeatId,
                    recommendedSeatId = "1C",
                    onSeatClick = { selectedSeatId = it.id }
                )
            }
        }

        composeRule.onNodeWithText("1C").performClick()

        composeRule.runOnIdle {
            assertEquals("1C", selectedSeatId)
        }
    }

    @Test
    fun leyenda_muestraItemRecomendado() {
        composeRule.setContent {
            AsombrateTheme {
                SeatLegend()
            }
        }

        composeRule.onNodeWithText("Recomendado ★").assertIsDisplayed()
    }
}
