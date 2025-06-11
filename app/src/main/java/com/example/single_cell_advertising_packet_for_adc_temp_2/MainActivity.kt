package com.example.single_cell_advertising_packet_for_adc_temp_2

/*  UI & analytics layer — unchanged except that
 *  (a) it no longer starts/stops the scan itself,
 *  (b) it consumes results from BleScannerBus.
 */

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.single_cell_advertising_packet_for_adc_temp_2.ui.theme.Single_Cell_Advertising_Packet_for_ADC_TEMP_2Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/* ---------- domain models ---------- */

enum class DisplayMode { FULL, TEMPERATURE, VOLTAGE, TIME }

data class DeviceState(
    val label: String,
    var adcValue: String = "--",
    var rawVoltage: Double = 0.0,
    var tempValue: String = "--",
    var rawTemp: Double = 0.0,
    var timeDeltaMs: Long = 0L,
    var lastPacketTimestamp: Long = 0L
)

/* ---------- main activity ---------- */

class MainActivity : ComponentActivity() {

    private val targetDevices = mapOf(
        "58:35:0F:DC:8D:BB" to "1",
        "58:35:0F:DC:8D:A9" to "2",
        "58:35:0F:DC:8D:BA" to "3",
        "58:35:0F:DC:8D:C9" to "4",
        "58:35:0F:DC:8D:B9" to "5",
        "58:35:0F:DC:8D:C7" to "6"
    )

    private val deviceStates = mutableStateMapOf<String, DeviceState>()
    private val lastPacketTimestamps = mutableMapOf<String, Long>()

    /* ---------- permissions ---------- */

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startScannerService()
    }

    private fun allPermissionsGranted() =
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).all { perm ->
            ActivityCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }

    /* ---------- lifecycle ---------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // initialise state map
        targetDevices.forEach { (addr, label) ->
            deviceStates[addr] = DeviceState(label, lastPacketTimestamp = System.currentTimeMillis())
        }

        if (allPermissionsGranted()) startScannerService() else permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )

        /* collect scan‑results coming from the foreground service */
        lifecycleScope.launch {
            BleScannerBus.scanResults.collectLatest { result ->
                val mac = result.device.address
                if (mac !in targetDevices) return@collectLatest // ⑤ filter in software

                val now      = System.currentTimeMillis()
                val deltaMs  = now - (lastPacketTimestamps[mac] ?: now)
                lastPacketTimestamps[mac] = now

                val bytes           = result.scanRecord?.bytes ?: return@collectLatest
                val firstSix        = bytes.take(6)
                val (voltStr, rawV) = convertAdcToVoltage(firstSix)
                val (tmpStr, rawT)  = convertToTemperature(firstSix)

                deviceStates[mac]?.let { cur ->
                    deviceStates[mac] = cur.copy(
                        adcValue   = voltStr,
                        rawVoltage = rawV,
                        tempValue  = tmpStr,
                        rawTemp    = rawT,
                        timeDeltaMs = deltaMs,
                        lastPacketTimestamp = now
                    )
                }
            }
        }

        /* ---------- UI ---------- */

        setContent {
            Single_Cell_Advertising_Packet_for_ADC_TEMP_2Theme {
                var displayMode by remember { mutableStateOf(DisplayMode.FULL) }

                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                            .padding(inner)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        /* title */
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(bottom = 16.dp)) {
                            Text("Module ID:", fontSize = 24.sp, color = Color.Black)
                            Text("1A5F", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }

                        /* grid */
                        val sorted = deviceStates.values.sortedBy { it.label.toInt() }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { displayMode = DisplayMode.FULL }
                        ) {
                            items(sorted) { dev ->
                                DeviceCircle(dev, displayMode)
                            }
                        }

                        /* bottom buttons */
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button({ displayMode = DisplayMode.TEMPERATURE }) { Text("Temperature") }
                            Button({ displayMode = DisplayMode.VOLTAGE })     { Text("Voltage") }
                            Button({ displayMode = DisplayMode.TIME })        { Text("Time") }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        stopService(Intent(this, ForegroundScannerService::class.java))
        super.onDestroy()
    }

    /* ---------- small helpers (unchanged) ---------- */

    private fun convertAdcToVoltage(bytes: List<Byte>) = run {
        val adc = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val v   = adc / 1000.0 * (178.0 + 150.0) / 150.0
        String.format("%.3f V", v) to v
    }

    private fun convertToTemperature(bytes: List<Byte>) = run {
        val raw  = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val c    = -45.0 + 175.0 * raw / 65535.0
        String.format("%.2f°C", c) to c
    }
}
