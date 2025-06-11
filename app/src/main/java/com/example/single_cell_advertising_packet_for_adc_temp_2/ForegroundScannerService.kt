package com.example.single_cell_advertising_packet_for_adc_temp_2

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class ForegroundScannerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothAdapter by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val scanner               by lazy { bluetoothAdapter.bluetoothLeScanner }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            BleScannerBus.scanResults.tryEmit(result)        // ④ no back‑pressure
        }
    }

    /* ---------- lifecycle ---------- */

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        startForeground(FOREGROUND_ID, buildNotification())

        holdWakeLock()

        // ② + ③ — run/refresh scanner in one long‑running coroutine
        scope.launch {
            var startCount30s = 0
            var windowStart   = SystemClock.elapsedRealtime()

            while (isActive) {
                // Throttle to ≤5 startScan()/30 s as required by the framework
                if (SystemClock.elapsedRealtime() - windowStart > 30_000) {
                    startCount30s = 0
                    windowStart   += 30_000
                }
                if (startCount30s < 5) {
                    startScannerOnce()
                    startCount30s++
                }

                delay(SCAN_DURATION_MS)          // 4–10 min window
                stopScanner()
            }
        }

        // Renew wakelock every ten minutes
        scope.launch {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(10))
                holdWakeLock()
            }
        }
    }

    override fun onBind(intent: Intent?) = null                 // not a bound service

    override fun onDestroy() {
        stopScanner()
        scope.cancel()
        wakeLock?.release()
        super.onDestroy()
    }

    /* ---------- BLE scan helpers ---------- */

    private fun startScannerOnce() {
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)          // continuous window
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)   // ① duplicates
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0L)                                        // no controller batching
            .setUseHardwareBatchingIfSupported(false)
            .setLegacy(true)                                           // stay on ch 37‑39
            .build()

        // ⑤ optional — remove filters → software‑filter in MainActivity
        scanner.startScan(/* filters = */ null, settings, scanCallback)
    }

    private fun stopScanner() = scanner.stopScan(scanCallback)

    /* ---------- Wake‑lock & notification ---------- */

    private var wakeLock: PowerManager.WakeLock? = null

    private fun holdWakeLock() {
        wakeLock?.release()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastBLE:Scanner")
            .apply { setReferenceCounted(false); acquire(WAKELOCK_TIMEOUT_MS) }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "BLE Scanner",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("BLE fast‑scan running")
            .setContentText("Listening on channels 37‑39 at full duty‑cycle.")
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID          = "ble_scanner"
        private const val FOREGROUND_ID       = 42
        private const val SCAN_DURATION_MS    = 4 * 60 * 1000L        // ④ restart cadence
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
