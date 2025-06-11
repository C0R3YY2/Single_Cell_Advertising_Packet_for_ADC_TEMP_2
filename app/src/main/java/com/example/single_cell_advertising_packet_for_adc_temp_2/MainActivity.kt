package com.example.single_cell_advertising_packet_for_adc_temp_2

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.single_cell_advertising_packet_for_adc_temp_2.ui.theme.Single_Cell_Advertising_Packet_for_ADC_TEMP_2Theme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

// Enum to manage the display state of the circles
enum class DisplayMode {
    FULL,
    TEMPERATURE,
    VOLTAGE,
    TIME
}

// Data class to hold the state for each device
data class DeviceState(
    val label: String,
    var adcValue: String = "--",
    var rawVoltage: Double = 0.0,
    var tempValue: String = "--",
    var rawTemp: Double = 0.0, // Raw temperature value
    var timeDeltaMs: Long = 0L,
    var lastPacketTimestamp: Long = 0L
)


class MainActivity : ComponentActivity() {

    // --- Robust Scanning Strategy ---
    // Restart scan every 4 minutes to prevent Android from downgrading it to opportunistic mode.
    private val SCAN_RESTART_INTERVAL_MS = 4 * 60 * 1000L

    // Rate-limiting to avoid BLE stack throttling (max 5 starts in 30s). We use 4 to be safe.
    private val scanStartTimestamps = mutableListOf<Long>()
    private val MAX_SCANS_IN_WINDOW = 4
    private val SCAN_WINDOW_MS = 30_000L

    private var scanRestartJob: Job? = null
    // --- End Scanning Strategy ---

    // A map to hold the state for each target device, keyed by its MAC address.
    private val deviceStates = mutableStateMapOf<String, DeviceState>()

    // Map to store the timestamp of the last received packet for each device
    private val lastPacketTimestamps = mutableMapOf<String, Long>()

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

    private val scanFilters: List<ScanFilter> by lazy {
        targetDevices.keys.map { address ->
            ScanFilter.Builder().setDeviceAddress(address).build()
        }
    }

    private val scanSettings: ScanSettings by lazy {
        ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .setLegacy(true) // Keeps scan running in the foreground on modern Android
            .build()
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

    private fun convertAdcToVoltage(bytes: List<Byte>): Pair<String, Double> {
        // First two bytes (e.g., 0x06D0) represent the ADC value
        val adcMsb = bytes[0].toInt() and 0xFF
        val adcLsb = bytes[1].toInt() and 0xFF

        // Combine bytes to get 16-bit value (big-endian format)
        val adcValue = (adcMsb shl 8) or adcLsb

        // Convert to voltage (in volts)
        val voltageInVolts = adcValue / 1000.0 * (178.0 + 150.0) / (150.0)

        // Add a space between the value and the unit
        val formattedString = String.format("%.3f V", voltageInVolts)
        return formattedString to voltageInVolts
    }

    private fun convertToTemperature(bytes: List<Byte>): Pair<String, Double> {
        // Next two bytes (e.g., 0x6750) represent the temperature value
        val tempMsb = bytes[2].toInt() and 0xFF
        val tempLsb = bytes[3].toInt() and 0xFF

        // Combine bytes to get 16-bit value (big-endian format)
        val tempValue = (tempMsb shl 8) or tempLsb

        // Apply temperature conversion formula
        val tempCelsius = -45.0 + (175.0 * tempValue / 65535.0)

        val formattedString = String.format("%.2f°C", tempCelsius)
        return formattedString to tempCelsius
    }


    private val scanResults = MutableSharedFlow<ScanResult>(extraBufferCapacity = 64)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanResults.tryEmit(result)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Process scan results on the main thread using a coroutine.
        lifecycleScope.launch {
            scanResults.collect { result ->
                val deviceAddress = result.device.address
                if (deviceAddress in targetDevices.keys) {
                    val currentTime = System.currentTimeMillis()
                    val lastTime = lastPacketTimestamps[deviceAddress]
                    // Calculate the time delta, defaulting to 0 for the first packet
                    val timeDeltaMs = if (lastTime != null) currentTime - lastTime else 0L

                    // Update the timestamp map for the next packet calculation
                    lastPacketTimestamps[deviceAddress] = currentTime

                    val bytes = result.scanRecord?.bytes ?: return@collect
                    val firstSixBytes = bytes.take(6)

                    deviceStates[deviceAddress]?.let { currentState ->
                        val (formattedVoltage, rawVoltage) = convertAdcToVoltage(firstSixBytes)
                        val (formattedTemp, rawTemp) = convertToTemperature(firstSixBytes)

                        val newState = currentState.copy(
                            adcValue = formattedVoltage,
                            rawVoltage = rawVoltage,
                            tempValue = formattedTemp,
                            rawTemp = rawTemp, // Store raw temperature
                            timeDeltaMs = timeDeltaMs,
                            lastPacketTimestamp = currentTime
                        )
                        deviceStates[deviceAddress] = newState
                    }
                }
            }
        }

        // Initialize the state map for all target devices
        targetDevices.forEach { (address, label) ->
            deviceStates[address] = DeviceState(label, lastPacketTimestamp = System.currentTimeMillis())
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

        setContent {
            Single_Cell_Advertising_Packet_for_ADC_TEMP_2Theme {
                var displayMode by remember { mutableStateOf(DisplayMode.FULL) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                            .padding(innerPadding)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Module ID Title Column
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Text(
                                text = "Module ID:",
                                color = Color.Black,
                                fontSize = 24.sp,
                            )
                            Text(
                                text = "1A5F",
                                color = Color.Black,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }


                        val sortedDeviceList = deviceStates.values.sortedBy { it.label.toInt() }

                        // Grid of Device Circles that fills the available space
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .weight(1f) // Allow grid to expand
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null // No ripple effect
                                ) {
                                    displayMode = DisplayMode.FULL
                                }
                        ) {
                            items(sortedDeviceList) { device ->
                                DeviceCircle(device = device, displayMode = displayMode)
                            }
                        }

                        // Bottom Button Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(onClick = { displayMode = DisplayMode.TEMPERATURE }) {
                                Text("Temperature")
                            }
                            Button(onClick = { displayMode = DisplayMode.VOLTAGE }) {
                                Text("Voltage")
                            }
                            Button(onClick = { displayMode = DisplayMode.TIME }) {
                                Text("Time")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun canStartScan(): Boolean {
        val now = System.currentTimeMillis()
        // Remove timestamps older than the rate-limit window.
        scanStartTimestamps.removeAll { now - it > SCAN_WINDOW_MS }
        // If we have made too many calls recently, deny the request.
        if (scanStartTimestamps.size >= MAX_SCANS_IN_WINDOW) {
            Log.w("MainActivity", "BLE scan throttled. Too many start requests in the last 30 seconds.")
            return false
        }
        return true
    }

    private fun startScan() {
        if (!canStartScan()) {
            return // Throttled
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return // Permissions not granted
        }

        // Record the timestamp of this scan start.
        scanStartTimestamps.add(System.currentTimeMillis())

        // Start the actual hardware scan.
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)

        // Cancel any existing restart job and schedule a new one.
        scanRestartJob?.cancel()
        scanRestartJob = lifecycleScope.launch {
            delay(SCAN_RESTART_INTERVAL_MS)
            restartScan()
        }
    }

    private fun restartScan() {
        lifecycleScope.launch {
            stopScan()
            // A brief delay ensures the BLE stack has time to cleanly stop the previous scan.
            delay(500)
            startScan()
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
        // Always cancel any pending restart job when we explicitly stop scanning.
        scanRestartJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
    }
}

// Top-level helper function for the shared color gradient.
private fun getGradientColor(factor: Float): Color {
    // Define the colors and stops for the custom gradient:
    // Blue -> Turquoise -> Green -> Yellow -> Orange -> Red
    val colors = listOf(
        Color.Blue,
        Color(0f, 1f, 1f), // Turquoise
        Color.Green,
        Color.Yellow,
        Color(1f, 0.647f, 0f), // Orange
        Color.Red
    )
    val stops = listOf(0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f)

    // Find the correct color segment and interpolate
    return when {
        factor <= stops.first() -> colors.first()
        factor >= stops.last() -> colors.last()
        else -> {
            val end = stops.indexOfFirst { it >= factor }
            val start = end - 1
            val startStop = stops[start]
            val endStop = stops[end]
            val startColor = colors[start]
            val endColor = colors[end]
            val localFactor = (factor - startStop) / (endStop - startStop)
            Color(
                red = startColor.red + localFactor * (endColor.red - startColor.red),
                green = startColor.green + localFactor * (endColor.green - startColor.green),
                blue = startColor.blue + localFactor * (endColor.blue - startColor.blue)
            )
        }
    }
}

// MODIFICATION: In DisplayMode.FULL, the color for voltage and temperature text
// is now set to Color.Black, removing the gradient.
@Composable
fun DeviceCircle(device: DeviceState, displayMode: DisplayMode, modifier: Modifier = Modifier) {

    val elapsedTime by produceState(initialValue = 0L, key1 = device.lastPacketTimestamp) {
        if (device.lastPacketTimestamp > 0) {
            while (true) {
                value = System.currentTimeMillis() - device.lastPacketTimestamp
                delay(33)
            }
        }
    }

    // Determine background color based on display mode
    val backgroundColor = when (displayMode) {
        DisplayMode.TIME -> {
            val maxTimeForGradientMs = 1000f
            val colorFactor = (1.0f - (elapsedTime.toFloat() / maxTimeForGradientMs)).coerceIn(0f, 1f)
            Color(red = colorFactor, green = colorFactor, blue = colorFactor)
        }
        DisplayMode.VOLTAGE -> {
            val factor = ((device.rawVoltage - 3.2) / (4.2 - 3.2)).coerceIn(0.0, 1.0).toFloat()
            getGradientColor(factor)
        }
        DisplayMode.TEMPERATURE -> {
            val factor = ((device.rawTemp - 20.0) / (30.0 - 20.0)).coerceIn(0.0, 1.0).toFloat()
            getGradientColor(factor)
        }
        DisplayMode.FULL -> Color.Transparent
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .aspectRatio(1f)
            .background(backgroundColor, CircleShape)
            .border(4.dp, Color.Black, CircleShape)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Determine the label's text color based on the mode
            val labelTextColor = when(displayMode) {
                DisplayMode.TIME -> if ((1.0f - (elapsedTime.toFloat() / 1000f)).coerceIn(0f, 1f) < 0.5f) Color.White else Color.Black
                else -> Color.Black // Black for FULL, VOLTAGE, and TEMPERATURE modes
            }

            Text(
                text = device.label,
                color = labelTextColor,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Display values based on the current mode
            when (displayMode) {
                DisplayMode.FULL -> {
                    val fontSize = 22.sp // Reduced font size to fit three lines of text

                    // Voltage Text with black color
                    Text(
                        text = device.adcValue,
                        color = Color.Black,
                        fontSize = fontSize,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Temperature Text with black color
                    Text(
                        text = device.tempValue,
                        color = Color.Black,
                        fontSize = fontSize,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Time Text with black color
                    Text(
                        text = "${device.timeDeltaMs} ms",
                        color = Color.Black,
                        fontSize = fontSize,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                DisplayMode.VOLTAGE -> {
                    Text(
                        text = device.adcValue,
                        color = Color.Black, // Always black text in this mode
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                DisplayMode.TEMPERATURE -> {
                    Text(
                        text = device.tempValue,
                        color = Color.Black, // Always black text in this mode
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                DisplayMode.TIME -> {
                    Text(
                        text = "${device.timeDeltaMs} ms",
                        color = labelTextColor, // Use same contrasting color as the label
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}