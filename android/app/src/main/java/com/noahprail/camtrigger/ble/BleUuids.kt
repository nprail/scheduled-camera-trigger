package com.noahprail.camtrigger.ble

import java.util.UUID

object BleUuid {
    val service: UUID = UUID.fromString("7a3c0001-5e9b-4d2a-8f61-c0a7b2d4e9f3")
    val deviceInfo: UUID = UUID.fromString("7a3c0002-5e9b-4d2a-8f61-c0a7b2d4e9f3")
    val status: UUID = UUID.fromString("7a3c0003-5e9b-4d2a-8f61-c0a7b2d4e9f3")
    val command: UUID = UUID.fromString("7a3c0004-5e9b-4d2a-8f61-c0a7b2d4e9f3")
    val response: UUID = UUID.fromString("7a3c0005-5e9b-4d2a-8f61-c0a7b2d4e9f3")
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
