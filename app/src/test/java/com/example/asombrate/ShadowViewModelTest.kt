package com.example.asombrate

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShadowViewModelTest {

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): ShadowViewModel {
        return ShadowViewModel(Application(), handle)
    }

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `selectVehicle persiste en SavedStateHandle`() {
        val handle = SavedStateHandle()
        val viewModel = newViewModel(handle)

        assertEquals(VehicleType.BUS, viewModel.selectedVehicle.value)

        viewModel.selectVehicle(VehicleType.CAR)

        assertEquals(VehicleType.CAR, viewModel.selectedVehicle.value)
        assertEquals("CAR", handle.get<String>("selected_vehicle"))

        val restoredViewModel = newViewModel(handle)
        assertEquals(VehicleType.CAR, restoredViewModel.selectedVehicle.value)
    }

    @Test
    fun `calculateShadow sin ubicaciones confirmadas emite error localizado`() {
        val viewModel = newViewModel()

        viewModel.calculateShadow()

        val state = viewModel.uiState.value
        assertTrue(state is ShadowState.Error)
        val error = state as ShadowState.Error
        assertEquals(R.string.error_confirm_before_calc, error.message.resId)
    }
}
