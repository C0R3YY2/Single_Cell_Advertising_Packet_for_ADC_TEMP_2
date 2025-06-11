package com.example.single_cell_advertising_packet_for_adc_temp_2

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ForegroundScannerService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var wakeLock: PowerManager.WakeLock? = null
    private var scanRestartJob: Job? = null
    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val lastPacketTimestamps = mutableMapOf<String, Long>()
    private val scanStartTimestamps = mutableListOf<Long>()

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "ScannerServiceChannel"
        private const val SCAN_RESTART_INTERVAL_MS = 4 * 60 * 1000L // 4 minutes
        private const val MAX_SCANS_IN_WINDOW = 4
        private const val SCAN_WINDOW_MS = 30_000L
    }

    private val scanFilters: List<ScanFilter> by lazy {
        DeviceDataRepository.targetDevices.keys.map { address ->
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
            .setLegacy(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We are not using a bound service
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        acquireWakeLock()
        startScanWithChecks()
        return START_STICKY // If the service is killed, it will be automatically restarted.
    }

    private fun startScanWithChecks() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            startScan()
        } else {
            Log.e("ScannerService", "Cannot start scan, BLUETOOTH_SCAN permission not granted.")
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        releaseWakeLock()
        serviceJob.cancel()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.release() // Release any existing wake lock before acquiring a new one.
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MultiCell::ScannerWakeLock")
        // The wake lock is acquired with a timeout to prevent it from running indefinitely.
        // It will be renewed on each scan restart cycle.
        wakeLock?.acquire(10 * 60 * 1000L /* 10 minutes */)
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createNotification(): Notification {
        // Create a notification channel for Android O and higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Scanner Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }

        // Intent to open the app when the notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Actively scanning for BLE devices...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a proper icon
            .setContentIntent(pendingIntent)
            .build()
    }

    // --- Scanning Logic ---
    private fun canStartScan(): Boolean {
        val now = System.currentTimeMillis()
        scanStartTimestamps.removeAll { now - it > SCAN_WINDOW_MS }
        if (scanStartTimestamps.size >= MAX_SCANS_IN_WINDOW) {
            Log.w("ScannerService", "BLE scan throttled.")
            return false
        }
        return true
    }

    private fun startScan() {
        if (!canStartScan()) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        scanStartTimestamps.add(System.currentTimeMillis())
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)
        Log.i("ScannerService", "Scan started.")

        // Schedule the next restart
        scanRestartJob?.cancel()
        scanRestartJob = serviceScope.launch {
            delay(SCAN_RESTART_INTERVAL_MS)
            restartScan()
        }
    }

    private fun stopScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanRestartJob?.cancel()
        Log.i("ScannerService", "Scan stopped.")
    }

    private fun restartScan() {
        serviceScope.launch {
            stopScan()
            delay(500) // Brief delay to ensure clean stop
            acquireWakeLock() // Renew the wake lock
            startScan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            serviceScope.launch {
                processScanResult(result)
            }
        }
    }

    private fun processScanResult(result: ScanResult) {
        val deviceAddress = result.device.address
        if (!DeviceDataRepository.targetDevices.containsKey(deviceAddress)) return

        val currentTime = System.currentTimeMillis()
        val lastTime = lastPacketTimestamps.getOrPut(deviceAddress) { currentTime }
        val timeDeltaMs = currentTime - lastTime
        lastPacketTimestamps[deviceAddress] = currentTime

        val bytes = result.scanRecord?.bytes ?: return
        val firstSixBytes = bytes.take(6)
        if (firstSixBytes.size < 4) return // Ensure we have enough data

        val (formattedVoltage, rawVoltage) = convertAdcToVoltage(firstSixBytes)
        val (formattedTemp, rawTemp) = convertToTemperature(firstSixBytes)

        val currentState = DeviceDataRepository.getDeviceState(deviceAddress) ?: return
        val newState = currentState.copy(
            adcValue = formattedVoltage,
            rawVoltage = rawVoltage,
            tempValue = formattedTemp,
            rawTemp = rawTemp,
            timeDeltaMs = timeDeltaMs,
            lastPacketTimestamp = currentTime
        )
        DeviceDataRepository.updateDeviceState(deviceAddress, newState)
    }

    private fun convertAdcToVoltage(bytes: List<Byte>): Pair<String, Double> {
        val adcMsb = bytes[0].toInt() and 0xFF
        val adcLsb = bytes[1].toInt() and 0xFF
        val adcValue = (adcMsb shl 8) or adcLsb
        val voltageInVolts = adcValue / 1000.0 * (178.0 + 150.0) / (150.0)
        return String.format("%.3f V", voltageInVolts) to voltageInVolts
    }

    private fun convertToTemperature(bytes: List<Byte>): Pair<String, Double> {
        val tempMsb = bytes[2].toInt() and 0xFF
        val tempLsb = bytes[3].toInt() and 0xFF
        val tempValue = (tempMsb shl 8) or tempLsb
        val tempCelsius = -45.0 + (175.0 * tempValue / 65535.0)
        return String.format("%.2f°C", tempCelsius) to tempCelsius
    }
}