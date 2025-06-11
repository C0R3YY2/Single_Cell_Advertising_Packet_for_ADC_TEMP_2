package com.example.single_cell_advertising_packet_for_adc_temp_2

import android.bluetooth.le.ScanResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A process‑wide hot stream that the foreground service writes to
 * and the UI layer (MainActivity) reads from.
 *
 * • replay = 0                → just push the newest packets
 * • extraBufferCapacity = 1024 → enough headroom for 1‑kHz advert rates
 * • DROP_OLDEST               → never block binder/scan threads
 */
object BleScannerBus {
    val scanResults = MutableSharedFlow<ScanResult>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}
