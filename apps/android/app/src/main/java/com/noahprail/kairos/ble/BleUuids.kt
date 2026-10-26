package com.noahprail.kairos.ble

import java.util.UUID

object BleUuid {
    val service: UUID = UUID.fromString("6b610001-6169-4f72-8f73-b3e1c5a97d24")
    val deviceInfo: UUID = UUID.fromString("6b610002-6169-4f72-8f73-b3e1c5a97d24")
    val status: UUID = UUID.fromString("6b610003-6169-4f72-8f73-b3e1c5a97d24")
    val command: UUID = UUID.fromString("6b610004-6169-4f72-8f73-b3e1c5a97d24")
    val response: UUID = UUID.fromString("6b610005-6169-4f72-8f73-b3e1c5a97d24")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** A device seen while scanning. */
data class DiscoveredDevice(
    /** The Bluetooth address (what Android connects by). */
    val id: String,
    val name: String,
    val rssi: Int,
    val lastSeen: Long,
) {
    /** The device ID from an advertised name like "Pad Cam-ABC123". */
    val deviceId: String?
        get() {
            val tail = name.split("-").last()
            val isId = tail.length == 6 && tail.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            return if (isId) tail.uppercase() else null
        }
}

enum class BluetoothState { UNKNOWN, UNSUPPORTED, UNAUTHORIZED, POWERED_OFF, POWERED_ON }
