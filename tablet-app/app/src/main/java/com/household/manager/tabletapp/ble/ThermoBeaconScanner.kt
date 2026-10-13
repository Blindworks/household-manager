package com.household.manager.tabletapp.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** Letztes geparstes Paket eines Sensors samt Empfangszeit (elapsedRealtime) und RSSI. */
data class SeenReading(val reading: ThermoBeaconReading, val rssi: Int, val seenAtElapsedMs: Long)

/**
 * Passiver Dauerscan auf die ThermoBeacon-Service-UUID im Stromsparmodus. Der Filter laeuft im
 * Bluetooth-Chip. Der Scan wird NICHT pro Zyklus neu gestartet (Android drosselt > 5 Starts in
 * 30 s still). Fail-safe wie die Kamera: ohne Adapter/Berechtigung nur eine Warnung; ein
 * Receiver auf ACTION_STATE_CHANGED startet den Scan neu, wenn Bluetooth wieder angeht.
 */
class ThermoBeaconScanner(
    private val context: Context,
    private val onFailure: (String) -> Unit
) {

    private val seen = ConcurrentHashMap<String, SeenReading>()
    private var scanning = false
    private var receiverRegistered = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val data = record.manufacturerSpecificData ?: return
            for (i in 0 until data.size()) {
                val reading = ThermoBeaconParser.parse(result.device.address.lowercase(), data.keyAt(i), data.valueAt(i))
                    ?: continue
                seen[reading.mac] = SeenReading(reading, result.rssi, SystemClock.elapsedRealtime())
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onFailure("BLE-Scan fehlgeschlagen, Code $errorCode")
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> startScan()
                BluetoothAdapter.STATE_OFF -> scanning = false
            }
        }
    }

    fun start() {
        if (!receiverRegistered) {
            context.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            receiverRegistered = true
        }
        startScan()
    }

    fun stop() {
        if (receiverRegistered) {
            context.unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        adapter()?.bluetoothLeScanner?.let { if (hasScanPermission()) runCatching { it.stopScan(callback) } }
        scanning = false
    }

    /** Liefert die frischen Messwerte und raeumt dabei veraltete aus, damit die Map nicht unbegrenzt waechst. */
    fun snapshot(): List<SeenReading> {
        val cutoff = SystemClock.elapsedRealtime() - MAX_AGE_MS
        seen.values.removeIf { it.seenAtElapsedMs < cutoff }
        return seen.values.toList()
    }

    private fun startScan() {
        if (scanning) return
        val adapter = adapter() ?: run { onFailure("Kein Bluetooth-Adapter"); return }
        if (!adapter.isEnabled) { onFailure("Bluetooth ist ausgeschaltet"); return }
        if (!hasScanPermission()) { onFailure("Bluetooth-Scan-Berechtigung fehlt"); return }
        val scanner = adapter.bluetoothLeScanner ?: run { onFailure("Kein BLE-Scanner"); return }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(ThermoBeaconParser.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            scanning = true
            Log.i(TAG, "BLE-Scan auf ThermoBeacon gestartet")
        } catch (ex: SecurityException) {
            onFailure("Bluetooth-Scan ohne Berechtigung: ${ex.message}")
        } catch (ex: IllegalStateException) {
            onFailure("Bluetooth-Scan nicht moeglich: ${ex.message}")
        }
    }

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            // Android 10/11: Scan braucht Standort — bewusst nicht unterstuetzt (siehe Spec)
            return false
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "ThermoBeaconScanner"
        const val MAX_AGE_MS = 300_000L
    }
}
