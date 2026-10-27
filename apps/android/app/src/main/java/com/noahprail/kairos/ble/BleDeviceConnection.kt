package com.noahprail.kairos.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.noahprail.kairos.protocol.DeviceClient
import com.noahprail.kairos.protocol.DeviceError
import com.noahprail.kairos.protocol.DeviceInfo
import com.noahprail.kairos.protocol.DeviceTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * One connected peripheral: the GATT characteristics behind a [DeviceTransport].
 *
 * Android's GATT API allows one outstanding operation at a time, so every
 * operation takes [gate] and waits for its callback before releasing it.
 */
@SuppressLint("MissingPermission") // BleCentral checks permissions before creating a connection
class BleDeviceConnection(
    private val context: Context,
    private val device: BluetoothDevice,
) : DeviceTransport {

    private val responseChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val statusChannel = Channel<ByteArray>(Channel.UNLIMITED)

    override val notifications: Flow<ByteArray> = responseChannel.receiveAsFlow()

    /** Status characteristic notifications (12-byte values). */
    val statusUpdates: Flow<ByteArray> = statusChannel.receiveAsFlow()

    /** Called once when an established connection drops (not for one the app closed). */
    var onDisconnect: ((Int) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var mtu = 23
    private var closed = false
    private var established = false

    private var infoChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var responseChar: BluetoothGattCharacteristic? = null
    private var firmwareChar: BluetoothGattCharacteristic? = null // only devices that can be updated have it

    private val gate = Mutex()
    private var connectResult: CompletableDeferred<Unit>? = null
    private var step: CompletableDeferred<Int>? = null
    private var read: CompletableDeferred<Pair<Int, ByteArray>>? = null

    /** Largest write that fits a single ATT packet (MTU - 3), and no more than an attribute value may hold (512). */
    override val maxWriteLength: Int get() = maxOf(minOf(mtu - 3, 512), 20)

    // region Setup

    suspend fun open(timeoutMs: Long) {
        val result = CompletableDeferred<Unit>()
        connectResult = result

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }

        try {
            withTimeout(timeoutMs) { result.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw DeviceError("timeout", "Could not connect to the device.")
        }

        // A bigger MTU means fewer fragments. The Pi may refuse and keep 23 bytes; that's fine.
        runStep { requestMtu(247) }

        val discovered = runStep { discoverServices() }
        if (discovered != BluetoothGatt.GATT_SUCCESS) throw DeviceError("not_found", "Could not read the device's services.")

        val service = gatt?.getService(BleUuid.service)
            ?: throw DeviceError("not_found", "This isn't a Kairos device (service not found).")
        infoChar = service.getCharacteristic(BleUuid.deviceInfo)
        statusChar = service.getCharacteristic(BleUuid.status)
        commandChar = service.getCharacteristic(BleUuid.command)
        responseChar = service.getCharacteristic(BleUuid.response)
        firmwareChar = service.getCharacteristic(BleUuid.firmware)
        if (infoChar == null || statusChar == null || commandChar == null || responseChar == null) {
            throw DeviceError("not_found", "The device is missing expected characteristics.")
        }

        subscribe(responseChar!!)
        subscribe(statusChar!!)
        // a firmware image is over a megabyte: ask for a short connection interval
        if (firmwareChar != null) gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        established = true
    }

    /**
     * Android keeps the GATT table of a bonded device. A device whose firmware gained the Firmware characteristic
     * (the first update-capable firmware, flashed over USB) looks like it lacks it. When Device Info says the
     * device can be updated but the characteristic is missing, drop the cache (a hidden but long-standing call)
     * and discover again.
     */
    suspend fun ensureFirmwareCharacteristic(info: DeviceInfo) {
        if (!info.canUpdate || firmwareChar != null) return
        val g = gatt ?: return
        val refreshed = runCatching { g.javaClass.getMethod("refresh").invoke(g) as? Boolean }.getOrNull() == true
        if (!refreshed) return
        kotlinx.coroutines.delay(500) // the stack needs a moment to forget the old table
        val discovered = runStep { discoverServices() }
        if (discovered != BluetoothGatt.GATT_SUCCESS) return
        val service = gatt?.getService(BleUuid.service) ?: return
        infoChar = service.getCharacteristic(BleUuid.deviceInfo) ?: infoChar
        statusChar = service.getCharacteristic(BleUuid.status) ?: statusChar
        commandChar = service.getCharacteristic(BleUuid.command) ?: commandChar
        responseChar = service.getCharacteristic(BleUuid.response) ?: responseChar
        firmwareChar = service.getCharacteristic(BleUuid.firmware)
        // the characteristic objects are new: turn notifications on for them again
        responseChar?.let { subscribe(it) }
        statusChar?.let { subscribe(it) }
        if (firmwareChar != null) gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    /** When Bluetooth is switched off, the stack's calls throw instead of calling back: that is a lost link. */
    private inline fun <T> stackCall(block: () -> T): T = try {
        block()
    } catch (_: android.os.DeadObjectException) {
        throw DeviceError.disconnected
    }

    private suspend fun runStep(start: BluetoothGatt.() -> Boolean): Int = gate.withLock {
        val g = gatt ?: throw DeviceError.disconnected
        if (closed) throw DeviceError.disconnected
        val deferred = CompletableDeferred<Int>()
        step = deferred
        if (!stackCall { g.start() }) {
            step = null
            throw DeviceError("gatt", "The Bluetooth stack rejected the request.")
        }
        deferred.await()
    }

    private suspend fun subscribe(characteristic: BluetoothGattCharacteristic) {
        val g = gatt ?: throw DeviceError.disconnected
        g.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(BleUuid.cccd)
            ?: throw DeviceError("not_found", "The device is missing expected characteristics.")
        val status = runStep {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                writeDescriptor(descriptor)
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw friendly(status)
    }

    // endregion

    // region Reads

    private suspend fun readCharacteristic(characteristic: BluetoothGattCharacteristic?): ByteArray = gate.withLock {
        val g = gatt
        if (characteristic == null || g == null || closed) throw DeviceError.disconnected
        val deferred = CompletableDeferred<Pair<Int, ByteArray>>()
        read = deferred
        if (!stackCall { g.readCharacteristic(characteristic) }) {
            read = null
            throw DeviceError("gatt", "The Bluetooth stack rejected the request.")
        }
        val (status, value) = deferred.await()
        if (status != BluetoothGatt.GATT_SUCCESS) throw friendly(status)
        value
    }

    suspend fun readDeviceInfo(): DeviceInfo {
        val data = readCharacteristic(infoChar)
        return try {
            DeviceInfo.parse(data) ?: throw DeviceError("bad_request", "The device sent unreadable identity data.")
        } catch (_: Exception) {
            throw DeviceError("bad_request", "The device sent unreadable identity data.")
        }
    }

    suspend fun readStatus(): ByteArray = readCharacteristic(statusChar)

    // endregion

    // region DeviceTransport

    override suspend fun write(data: ByteArray) {
        val characteristic = commandChar
        val status = runStep {
            if (characteristic == null) {
                false
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                writeCharacteristic(characteristic)
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw friendly(status)
    }

    override suspend fun writeFirmware(data: ByteArray) {
        val characteristic = firmwareChar ?: throw DeviceError(
            "stale_services",
            "This phone remembers an older version of the device's Bluetooth services. Remove the device in the phone's Bluetooth settings, then connect again.",
        )
        // Each write waits for the stack to take it, which is the back-pressure. A write without response can be
        // refused as "busy" for a moment after the previous callback, so a busy answer is retried shortly.
        gate.withLock {
            val g = gatt ?: throw DeviceError.disconnected
            var busyRetries = 0
            while (true) {
                if (closed) throw DeviceError.disconnected
                val deferred = CompletableDeferred<Int>()
                step = deferred
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    stackCall { g.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    characteristic.value = data
                    @Suppress("DEPRECATION")
                    if (stackCall { g.writeCharacteristic(characteristic) }) BluetoothGatt.GATT_SUCCESS else ERROR_BUSY
                }
                if (code == BluetoothGatt.GATT_SUCCESS) {
                    val status = deferred.await()
                    if (status != BluetoothGatt.GATT_SUCCESS) throw friendly(status)
                    return@withLock
                }
                step = null
                if (code != ERROR_BUSY || ++busyRetries > 200) {
                    throw DeviceError("gatt", "The Bluetooth stack rejected a firmware write (code $code).")
                }
                kotlinx.coroutines.delay(5)
            }
        }
    }

    private companion object {
        /** BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY */
        const val ERROR_BUSY = 201
    }

    // endregion

    // region Teardown

    /** Close the link. Safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        failPending()
    }

    private fun failPending() {
        connectResult?.completeExceptionally(DeviceError.disconnected)
        step?.completeExceptionally(DeviceError.disconnected)
        read?.completeExceptionally(DeviceError.disconnected)
        responseChannel.close()
        statusChannel.close()
    }

    // endregion

    // region Callbacks

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connectResult?.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasEstablished = established
                    val byUs = closed
                    closed = true
                    runCatching { g.close() }
                    failPending()
                    if (wasEstablished && !byUs) onDisconnect?.invoke(status)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            step?.complete(status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            step?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            step?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            step?.complete(status)
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            read?.complete(status to (characteristic.value ?: ByteArray(0)))
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            read?.complete(status to value)
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            deliver(characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(characteristic.uuid, value)
        }
    }

    private fun deliver(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            BleUuid.response -> responseChannel.trySend(value)
            BleUuid.status -> statusChannel.trySend(value)
        }
    }

    // endregion

    /** Turn the stack's pairing failures into something actionable. */
    private fun friendly(status: Int): DeviceError = when (status) {
        BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION ->
            DeviceError(
                "encryption",
                "Android couldn't pair with the device. If it was paired before, remove it under Settings › Connected devices, then try again.",
            )
        else -> DeviceError("gatt", "Bluetooth error $status.")
    }
}
