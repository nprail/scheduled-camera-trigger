package com.noahprail.camtrigger.ble

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.core.content.ContextCompat
import com.noahprail.camtrigger.protocol.DeviceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Scans for camera triggers and opens connections. */
@SuppressLint("MissingPermission") // every entry point checks hasPermissions() first
class BleCentral(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter

    private val _state = MutableStateFlow(BluetoothState.UNKNOWN)
    val state: StateFlow<BluetoothState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val seen = mutableMapOf<String, DiscoveredDevice>()
    private var scanning = false
    private var pruneJob: Job? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshState()
    }

    init {
        ContextCompat.registerReceiver(
            app, stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshState()
    }

    fun close() {
        stopScan()
        runCatching { app.unregisterReceiver(stateReceiver) }
    }

    // region State

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Re-read Bluetooth availability, permissions and power state. Call after a permission result. */
    fun refreshState() {
        val next = when {
            adapter == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ->
                BluetoothState.UNSUPPORTED
            !hasPermissions() -> BluetoothState.UNAUTHORIZED
            !adapter.isEnabled -> BluetoothState.POWERED_OFF
            else -> BluetoothState.POWERED_ON
        }
        val previous = _state.value
        _state.value = next
        if (next != BluetoothState.POWERED_ON) {
            if (scanning) stopScan()
            seen.clear()
            _devices.value = emptyList()
        } else if (previous != BluetoothState.POWERED_ON && wantScan) {
            startScan()
        }
    }

    // endregion

    // region Scanning

    private var wantScan = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName
                ?: runCatching { result.device.name }.getOrNull()
                ?: "Camera Trigger"
            seen[result.device.address] = DiscoveredDevice(
                id = result.device.address,
                name = name,
                rssi = result.rssi,
                lastSeen = SystemClock.elapsedRealtime(),
            )
            publish()
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    fun startScan() {
        wantScan = true
        if (scanning || _state.value != BluetoothState.POWERED_ON) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        scanning = true
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUuid.service)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (true) {
                delay(2_000)
                prune()
            }
        }
    }

    fun stopScan() {
        wantScan = false
        if (scanning && hasPermissions() && adapter?.isEnabled == true) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
        scanning = false
        pruneJob?.cancel()
        pruneJob = null
    }

    private fun prune() {
        val cutoff = SystemClock.elapsedRealtime() - 8_000
        val stale = seen.filterValues { it.lastSeen < cutoff }.keys
        if (stale.isEmpty()) return
        stale.forEach(seen::remove)
        publish()
    }

    private fun publish() {
        _devices.value = seen.values.sortedByDescending { it.rssi }
    }

    // endregion

    // region Connecting

    /** Connect, discover the service and subscribe, ready for a `DeviceClient`. */
    suspend fun connect(id: String, timeoutMs: Long = 15_000): BleDeviceConnection {
        if (!hasPermissions()) throw DeviceError("unauthorized_bt", "Bluetooth permission is needed to connect.")
        val device = try {
            adapter?.getRemoteDevice(id)
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw DeviceError("not_found", "The device is no longer in range.")

        val connection = BleDeviceConnection(app, device)
        try {
            connection.open(timeoutMs)
        } catch (e: Throwable) {
            connection.close()
            throw e
        }
        return connection
    }

    // endregion

    private companion object {
        fun requiredPermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}

/** The runtime permissions to request, for the running Android version. */
fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
