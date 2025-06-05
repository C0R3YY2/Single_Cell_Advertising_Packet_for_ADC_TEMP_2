package com.example.single_cell_advertising_packet_for_adc_temp_2

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.single_cell_advertising_packet_for_adc_temp_2.ui.theme.Single_Cell_Advertising_Packet_for_ADC_TEMP_2Theme

// Data class to hold the state for each device
data class DeviceState(
    val label: String,
    var adcValue: String = "--",
    var tempValue: String = "--"
)

class MainActivity : ComponentActivity() {

    private val advertisingPackets = mutableStateListOf<String>()
    private val maxEntries = 20 // Max entries for the raw data log

    // A map to hold the state for each target device, keyed by its MAC address.
    // Using mutableStateMapOf to ensure Compose recomposes when items are updated.
    private val deviceStates = mutableStateMapOf<String, DeviceState>()

    // Map of target BLE device addresses to their assigned labels
    private val targetDevices = mapOf(
        "58:35:0F:DC:8D:BB" to "1",
        "58:35:0F:DC:8D:A9" to "2",
        "58:35:0F:DC:8D:BA" to "3",
        "58:35:0F:DC:8D:C9" to "4",
        "58:35:0F:DC:8D:B9" to "5",
        "58:35:0F:DC:8D:C7" to "6"
    )

    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startScan()
            }
        }

    private fun arePermissionsGranted(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun convertAdcToVoltage(bytes: List<Byte>): String {
        // First two bytes (e.g., 0x06D0) represent the ADC value
        val adcMsb = bytes[0].toInt() and 0xFF
        val adcLsb = bytes[1].toInt() and 0xFF

        // Combine bytes to get 16-bit value (big-endian format)
        val adcValue = (adcMsb shl 8) or adcLsb

        // Convert to voltage (in volts)
        val voltageInVolts = adcValue / 1000.0 * (178.0 + 150.0) / (150.0)

        return String.format("%.2fV", voltageInVolts)
    }

    private fun convertToTemperature(bytes: List<Byte>): String {
        // Next two bytes (e.g., 0x6750) represent the temperature value
        val tempMsb = bytes[2].toInt() and 0xFF
        val tempLsb = bytes[3].toInt() and 0xFF

        // Combine bytes to get 16-bit value (big-endian format)
        val tempValue = (tempMsb shl 8) or tempLsb

        // Apply temperature conversion formula
        val tempCelsius = -45.0 + (175.0 * tempValue / 65535.0)

        return String.format("%.2f°C", tempCelsius)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceAddress = result.device.address
            // Check if the scanned device is one of our targets
            if (deviceAddress in targetDevices.keys) {
                val bytes = result.scanRecord?.bytes ?: return
                val firstSixBytes = bytes.take(6)
                val hex = firstSixBytes.joinToString(" ") { String.format("%02X", it) }

                // Get the state for the specific device and update it
                deviceStates[deviceAddress]?.let { currentState ->
                    val newState = currentState.copy(
                        adcValue = convertAdcToVoltage(firstSixBytes),
                        tempValue = convertToTemperature(firstSixBytes)
                    )
                    deviceStates[deviceAddress] = newState

                    // Add labeled raw data to the log
                    advertisingPackets.add("[${currentState.label}] $hex")

                    if (advertisingPackets.size > maxEntries * targetDevices.size) { // Adjust log size
                        advertisingPackets.removeAt(0)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize the state map for all target devices
        targetDevices.forEach { (address, label) ->
            deviceStates[address] = DeviceState(label)
        }

        if (arePermissionsGranted()) {
            startScan()
        } else {
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }

        /*setContent {
            Single_Cell_Advertising_Packet_for_ADC_TEMP_2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Get the list of devices, sorted by label for a consistent order
                        val sortedDeviceList = remember(deviceStates) {
                            deviceStates.values.sortedBy { it.label.toInt() }
                        }

                        // Display the list of devices and their data
                        DeviceDataTable(devices = sortedDeviceList)

                        // Display raw advertising data
                        Text(
                            text = "Raw Data Log:",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
                        )
                        AdvertisingList(
                            packets = advertisingPackets,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }*/

        setContent {
            Single_Cell_Advertising_Packet_for_ADC_TEMP_2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // FIX: Remove the 'remember' block to ensure the list is always fresh.
                        // The list is re-sorted on each recomposition, which is fine for a small list.
                        val sortedDeviceList = deviceStates.values.sortedBy { it.label.toInt() }

                        // Display the list of devices and their data
                        DeviceDataTable(devices = sortedDeviceList)

                        // Display raw advertising data
                        Text(
                            text = "Raw Data Log:",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
                        )
                        AdvertisingList(
                            packets = advertisingPackets,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    private fun startScan() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
        }
    }

    private fun stopScan() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }
}

@Composable
fun DeviceDataTable(devices: List<DeviceState>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(devices) { device ->
            DeviceDataCard(device = device)
        }
    }
}

@Composable
fun DeviceDataCard(device: DeviceState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Device ${device.label}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = device.adcValue,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = device.tempValue,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
fun AdvertisingList(packets: List<String>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal=16.dp)) {
        items(packets) { packet ->
            Text(
                text = packet,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}