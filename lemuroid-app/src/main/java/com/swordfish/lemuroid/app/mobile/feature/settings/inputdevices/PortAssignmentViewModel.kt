package com.swordfish.lemuroid.app.mobile.feature.settings.inputdevices

import android.view.InputDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PortAssignmentViewModel(
    private val inputDeviceManager: InputDeviceManager,
) : ViewModel() {
    class Factory(val inputDeviceManager: InputDeviceManager) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PortAssignmentViewModel(inputDeviceManager) as T
        }
    }

    data class DeviceEntry(val name: String, val descriptor: String)

    data class State(
        val orderedDevices: List<DeviceEntry> = emptyList(),
    )

    val uiState =
        initializeState()
            .stateIn(viewModelScope, SharingStarted.Lazily, State())

    fun moveUp(index: Int) {
        if (index <= 0) return
        val current = uiState.value.orderedDevices.toMutableList()
        val tmp = current[index - 1]
        current[index - 1] = current[index]
        current[index] = tmp
        saveOrder(current)
    }

    fun moveDown(index: Int) {
        val current = uiState.value.orderedDevices.toMutableList()
        if (index >= current.size - 1) return
        val tmp = current[index + 1]
        current[index + 1] = current[index]
        current[index] = tmp
        saveOrder(current)
    }

    fun resetOrder() {
        viewModelScope.launch {
            inputDeviceManager.savePortOrder(emptyList())
        }
    }

    private fun saveOrder(devices: List<DeviceEntry>) {
        viewModelScope.launch {
            inputDeviceManager.savePortOrder(devices.map { it.descriptor })
        }
    }

    private fun initializeState(): Flow<State> {
        return combine(
            inputDeviceManager.getEnabledInputsObservable(),
            inputDeviceManager.getPortOrderFlow(),
        ) { devices, portOrder ->
            val portOrderSet = portOrder.toSet()
            val ordered = portOrder.mapNotNull { desc -> devices.find { it.descriptor == desc }?.toEntry() }
            val remaining = devices.filter { d -> d.descriptor !in portOrderSet }.map { it.toEntry() }
            State(orderedDevices = ordered + remaining)
        }
    }

    private fun InputDevice.toEntry() = DeviceEntry(name = name, descriptor = descriptor)
}
