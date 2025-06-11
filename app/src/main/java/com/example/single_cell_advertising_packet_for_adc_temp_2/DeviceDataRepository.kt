package com.example.single_cell_advertising_packet_for_adc_temp_2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A singleton object that acts as a single source of truth for device data.
 * This allows the ForegroundScannerService to update the data and the MainActivity
 * to observe it without a direct dependency on each other.
 */
object DeviceDataRepository {

    // The internal mutable map that the service will update.
    private val _deviceStates = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    // The external, read-only StateFlow that the UI will observe.
    val deviceStates = _deviceStates.asStateFlow()

    // Map of target BLE device addresses to their assigned labels
    val targetDevices = mapOf(
        "58:35:0F:DC:8D:BB" to "1",
        "58:35:0F:DC:8D:A9" to "2",
        "58:35:0F:DC:8D:BA" to "3",
        "58:35:0F:DC:8D:C9" to "4",
        "58:35:0F:DC:8D:B9" to "5",
        "58:35:0F:DC:8D:C7" to "6"
    )

    init {
        // Initialize the state map with default values for all target devices.
        val initialMap = targetDevices.mapValues { (_, label) ->
            DeviceState(label = label)
        }
        _deviceStates.value = initialMap
    }

    /**
     * Updates the state of a specific device. Called by the scanner service.
     * @param address The MAC address of the device.
     * @param newState The new state object for the device.
     */
    fun updateDeviceState(address: String, newState: DeviceState) {
        val currentMap = _deviceStates.value.toMutableMap()
        currentMap[address] = newState
        _deviceStates.value = currentMap // Assigning a new map triggers the StateFlow update
    }

    /**
     * Gets the current state for a device or returns null if not found.
     * @param address The MAC address of the device.
     */
    fun getDeviceState(address: String): DeviceState? {
        return _deviceStates.value[address]
    }
}