package com.noahprail.camtrigger.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.noahprail.camtrigger.protocol.DeviceClient
import com.noahprail.camtrigger.protocol.DeviceError
import com.noahprail.camtrigger.protocol.DeviceInfo
import com.noahprail.camtrigger.protocol.DeviceTransport
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

    private val gate = Mutex()
    private var connectResult: CompletableDeferred<Unit>? = null
    private var step: CompletableDeferred<Int>? = null
    private var read: CompletableDeferred<Pair<Int, ByteArray>>? = null

    /** Largest write that fits a single ATT packet (MTU - 3). */
    override val maxWriteLength: Int get() = maxOf(mtu - 3, 20)

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
            ?: throw DeviceError("not_found", "This isn't a camera trigger (service not found).")
        infoChar = service.getCharacteristic(BleUuid.deviceInfo)
        statusChar = service.getCharacteristic(BleUuid.status)
        commandChar = service.getCharacteristic(BleUuid.command)
        responseChar = service.getCharacteristic(BleUuid.response)
        if (infoChar == null || statusChar == null || commandChar == null || responseChar == null) {
            throw DeviceError("not_found", "The device is missing expected characteristics.")
        }

        subscribe(responseChar!!)
        subscribe(statusChar!!)
        established = true
    }

    private suspend fun runStep(start: BluetoothGatt.() -> Boolean): Int = gate.withLock {
        val g = gatt ?: throw DeviceError.disconnected
        if (closed) throw DeviceError.disconnected
        val deferred = CompletableDeferred<Int>()
        step = deferred
        if (!g.start()) {
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
        if (!g.readCharacteristic(characteristic)) {
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
            DeviceClient.json.decodeFromString(DeviceInfo.serializer(), data.decodeToString())
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
