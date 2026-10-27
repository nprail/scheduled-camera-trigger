package com.noahprail.kairos.protocol

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

// region Characteristic values

/** Device Info characteristic (docs/ble-protocol.md, Device Info). */
data class DeviceInfo(
    val v: Int,
    /** The 6 hex digits of the device id, upper case. */
    val id: String,
    val name: String,
    val cam: String,
    val fw: String,
    /** The camera types this device can drive. A device without the Ethernet module cannot drive a Z CAM. */
    val cams: List<String> = CameraConfig.cameraTypes,
    /** Capability bits; bit0 is firmware update over Bluetooth. 0 when the device does not send them. */
    val caps: Int = 0,
    /** The chip, when the device says (an update needs it to pick the right image). */
    val chip: Chip? = null,
    /** The largest firmware image the device accepts, in bytes. 0 when not updatable. */
    val slot: Int = 0,
) {
    /** The device can be updated over Bluetooth and the app knows which image fits it. */
    val canUpdate: Boolean get() = caps and 1 != 0 && chip != null && slot > 0

    companion object {
        fun parse(data: ByteArray): DeviceInfo? {
            val r = ByteReader(data)
            val v = r.u8()
            val id = r.bytes(3).joinToString("") { "%02X".format(it) }
            val cam = r.u8()
            val mask = r.u8()
            val fw = r.string()
            val name = r.string()
            if (!r.ok) return null
            // optional trailing fields, missing on devices that cannot be updated
            val caps = if (r.remaining >= 1) r.u8() else 0
            val chip = if (r.remaining >= 1) Chip.fromDeviceValue(r.u8()) else null
            val slot = if (r.remaining >= 4) r.u32().toInt() else 0
            val cams = CameraConfig.cameraTypes.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            return DeviceInfo(v, id, name, CameraConfig.cameraTypes.getOrElse(cam) { "generic" }, fw, cams, caps, chip, slot)
        }
    }
}

/** The fixed 13-byte Status characteristic. */
data class StatusValue(
    val flags: Int,
    val phase: Phase,
    val configRev: Long,
    /** Unix seconds, 0 if none. */
    val nextEventAt: Long,
    val nextEventType: EventType,
    val attemptsRemaining: Int,
    /** Index (in the config's attempts) of the attempt that owns the next event. */
    val nextAttempt: Int?,
) {
    enum class Phase { IDLE, AWAKE, RECORDING, TEST, ERROR }
    enum class EventType { NONE, WAKE, START, STOP }

    val recording get() = flags and RECORDING != 0
    val cameraReady get() = flags and CAMERA_READY != 0
    val pairingOpen get() = flags and PAIRING_OPEN != 0
    val clockValid get() = flags and CLOCK_VALID != 0

    val nextEventDate: Instant? get() = if (nextEventAt == 0L) null else Instant.ofEpochSecond(nextEventAt)

    companion object {
        const val LENGTH = 13
        const val RECORDING = 1 shl 0
        const val CAMERA_READY = 1 shl 1
        const val PAIRING_OPEN = 1 shl 2
        const val CLOCK_VALID = 1 shl 3

        fun parse(data: ByteArray): StatusValue? {
            val r = ByteReader(data)
            val flags = r.u8()
            val phase = r.u8()
            val rev = r.u32()
            val at = r.u32()
            val type = r.u8()
            val remaining = r.u8()
            val next = r.u8()
            if (!r.ok) return null
            return StatusValue(
                flags = flags,
                phase = Phase.entries.getOrElse(phase) { Phase.ERROR },
                configRev = rev,
                nextEventAt = at,
                nextEventType = EventType.entries.getOrElse(type) { EventType.NONE },
                attemptsRemaining = remaining,
                nextAttempt = if (next == 0xFF) null else next,
            )
        }
    }
}

// endregion

// region Responses

class HelloResponse(
    val v: Int,
    val paired: Boolean,
    val nonce: ByteArray,
    val nowMs: Long,
    val maxMessage: Int,
) {
    val now: Instant get() = Instant.ofEpochMilli(nowMs)
}

/** One scheduled attempt and its state. The times come from the config; the device only owns the state. */
data class Job(
    val index: Int,
    val name: String,
    val launchDate: Instant,
    /** "pending", "active", "complete" or "missed". */
    val state: String,
)

data class LogEntry(val seq: Int, val ts: Long, val ns: String, val msg: String) {
    val date: Instant get() = Instant.ofEpochSecond(ts)
}

data class ClockSync(
    /** Device clock minus phone clock, seconds (round-trip compensated). */
    val offset: Double,
    /** Whether the device clock was set. */
    val applied: Boolean,
)

// endregion

// region Config

private val localIds = AtomicLong(1)

data class CameraConfig(
    val name: String? = null,
    val cameraType: String = "generic",
    /** All durations are seconds. */
    val wakeUpTimeout: Double = 5.0,
    val startBefore: Double = 20.0,
    val endAfter: Double = 30.0,
    val zcam: Zcam = Zcam(),
    val attempts: List<Attempt> = emptyList(),
) {
    data class Zcam(val cameraIp: String? = null)

    data class Attempt(
        val name: String,
        /** T-0 in Unix seconds. */
        val time: Long,
        /** Local identity for the UI; never sent to the device. */
        val localId: Long = localIds.getAndIncrement(),
    ) {
        val instant: Instant get() = Instant.ofEpochSecond(time)

        fun withInstant(instant: Instant) = copy(time = instant.epochSecond.coerceIn(0, 0xFFFFFFFFL))

        companion object {
            fun create(name: String, time: Instant) = Attempt(name, time.epochSecond.coerceIn(0, 0xFFFFFFFFL))
        }
    }

    /** Equality as the device sees it: attempts compare by name and time, not local id. */
    fun sameAs(other: CameraConfig): Boolean =
        copy(attempts = attempts.map { it.copy(localId = 0) }) ==
            other.copy(attempts = other.attempts.map { it.copy(localId = 0) })

    companion object {
        val cameraTypes = listOf("generic", "zcam")
    }
}

// endregion

// region Events

data class JobEvent(
    /** Index in the config's attempts. */
    val attempt: Int,
    val step: String,
    val at: Instant,
)

data class TestEvent(val step: String, val ok: Boolean, val error: String? = null)

/** Progress of a firmware transfer, from the device (docs/ble-protocol.md, fw.*). */
data class FirmwareEvent(val state: State, val offset: Int, val reason: Int = 0) {
    enum class State { RECEIVING, VERIFYING, READY, FAILED }
}

sealed interface DeviceEvent {
    data class Job(val event: JobEvent) : DeviceEvent
    data class Config(val revision: Int) : DeviceEvent
    data class Test(val event: TestEvent) : DeviceEvent
    data class Log(val entry: LogEntry) : DeviceEvent
}

// endregion
