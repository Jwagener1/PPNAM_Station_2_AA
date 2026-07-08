package com.ppnam.station2aa.data.rfid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class DataWedgeReceiver : BroadcastReceiver() {

    @Inject lateinit var scanEventBus: ScanEventBus

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive action=${intent.action} extras=${intent.extras?.keySet()?.associateWith { intent.extras?.get(it) }}")
        when (intent.action) {
            ACTION_SCAN -> {
                val source = intent.getStringExtra(EXTRA_SOURCE) ?: ""
                val data = intent.getStringExtra(EXTRA_DATA)
                if (data == null) {
                    Log.w(TAG, "ACTION_SCAN received with no $EXTRA_DATA extra - ignoring")
                    return
                }
                val labelType = intent.getStringExtra(EXTRA_LABEL_TYPE) ?: ""

                val event = if (source.equals("RFID", ignoreCase = true) ||
                    labelType.startsWith("LABEL-TYPE-RFID", ignoreCase = true)) {
                    ScanEvent.RfidTag(tagId = data, timestamp = Instant.now())
                } else {
                    ScanEvent.Barcode(value = data, format = labelType, timestamp = Instant.now())
                }
                Log.d(TAG, "emitting $event")
                scanEventBus.emit(event)
            }
            ACTION_CHAINWAY_BARCODE -> {
                val data = intent.getStringExtra(EXTRA_CHAINWAY_DATA)
                if (data == null) {
                    Log.w(TAG, "ACTION_CHAINWAY_BARCODE received with no '$EXTRA_CHAINWAY_DATA' extra - ignoring")
                    return
                }
                val event = ScanEvent.Barcode(value = data, format = "", timestamp = Instant.now())
                Log.d(TAG, "emitting $event")
                scanEventBus.emit(event)
            }
            ACTION_CHAINWAY_RFID -> {
                val data = intent.getStringExtra(EXTRA_CHAINWAY_DATA)
                if (data == null) {
                    Log.w(TAG, "ACTION_CHAINWAY_RFID received with no '$EXTRA_CHAINWAY_DATA' extra - ignoring")
                    return
                }
                val event = ScanEvent.RfidTag(tagId = data, timestamp = Instant.now())
                Log.d(TAG, "emitting $event")
                scanEventBus.emit(event)
            }
            else -> Log.w(TAG, "onReceive: unrecognized action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "DataWedgeReceiver"
        const val ACTION_SCAN = "com.ppnam.station2aa.ACTION_SCAN"
        const val EXTRA_DATA = "com.symbol.datawedge.data_string"
        const val EXTRA_SOURCE = "com.symbol.datawedge.source"
        const val EXTRA_LABEL_TYPE = "com.symbol.datawedge.label_type"

        // Chainway RFID/barcode reader broadcasts — the actual hardware in use.
        const val ACTION_CHAINWAY_BARCODE = "com.scanner.broadcast"
        const val ACTION_CHAINWAY_RFID = "com.rscja.scanner.action.scanner.RFID"
        const val EXTRA_CHAINWAY_DATA = "data"
    }
}
