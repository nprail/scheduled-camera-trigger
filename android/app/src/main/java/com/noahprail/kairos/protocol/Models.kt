package com.noahprail.kairos.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

// region Characteristic values

/** Device Info characteristic (JSON). */
@Serializable
data class DeviceInfo(
    val v: Int,
    val id: String,
    val name: String,
    val cam: String,
    val fw: String,
)

/** The fixed 12-byte Status characteristic. */
data class StatusValue(
    val flags: Int,
    val phase: Phase,
    val configRev: Long,
    /** Unix seconds, 0 if none. */
    val nextEventAt: Long,
    val nextEventType: EventType,
    val attemptsRemaining: Int,
) {
    enum class Phase { IDLE, AWAKE, RECORDING, TEST, ERROR }
    enum class EventType { NONE, WAKE, START, STOP }

    val recording get() = flags and RECORDING != 0
    val cameraReady get() = flags and CAMERA_READY != 0
    val pairingOpen get() = flags and PAIRING_OPEN != 0
    val clockSet get() = flags and CLOCK_SET != 0

    val nextEventDate: Instant? get() = if (nextEventAt == 0L) null else Instant.ofEpochSecond(nextEventAt)

    companion object {
        const val RECORDING = 1 shl 0
        const val CAMERA_READY = 1 shl 1
        const val PAIRING_OPEN = 1 shl 2
        const val CLOCK_SET = 1 shl 3

        fun parse(data: ByteArray): StatusValue? {
            if (data.size < 12) return null
            fun u8(o: Int) = data[o].toInt() and 0xFF
            fun u32(o: Int) = u8(o).toLong() or (u8(o + 1).toLong() shl 8) or
                (u8(o + 2).toLong() shl 16) or (u8(o + 3).toLong() shl 24)

            return StatusValue(
                flags = u8(0),
                phase = Phase.entries.getOrElse(u8(1)) { Phase.ERROR },
                configRev = u32(2),
                nextEventAt = u32(6),
                nextEventType = EventType.entries.getOrElse(u8(10)) { EventType.NONE },
                attemptsRemaining = u8(11),
            )
        }
    }
}

// endregion

// region Responses

@Serializable
data class HelloResponse(
    val v: Int,
    val nonce: String,
    val authRequired: Boolean,
    val paired: Boolean,
    val now: String,
    val maxMessage: Int,
)

@Serializable
data class AuthResponse(val proof: String)

@Serializable
data class TimeResponse(val now: String, val offsetMs: Int? = null)

@Serializable
data class StatusInfo(
    val phase: String,
    val recording: Boolean,
    val cameraReady: Boolean,
    val configRev: Int,
    val next: Next? = null,
) {
    @Serializable
    data class Next(val type: String, val at: String, val attempt: String) {
        val date: Instant? get() = DeviceDate.parse(at)
    }
}

@Serializable
data class Job(
    val id: String,
    val name: String,
    val camera: String,
    val wakeUpTime: String,
    val recordTime: String,
    val launchTime: String,
    val stopTime: String,
    val state: String,
) {
    val launchDate: Instant? get() = DeviceDate.parse(launchTime)
}

@Serializable
internal data class JobsPage(val jobs: List<Job>, val total: Int, val more: Boolean)

@Serializable
data class LogEntry(val seq: Int, val ts: String, val ns: String, val msg: String) {
    val date: Instant? get() = DeviceDate.parse(ts)
}

@Serializable
internal data class LogsPage(val logs: List<LogEntry>, val last: Int, val more: Boolean)

@Serializable
internal data class ConfigEnvelope(val config: CameraConfig)

@Serializable
internal data class ConfigSetResponse(val configRev: Int)

@Serializable
internal data class CameraResponse(val recording: Boolean)

@Serializable
internal data class StartedResponse(val started: Boolean)

@Serializable
internal class Empty

data class ClockSync(
    /** Device clock minus phone clock, seconds (round-trip compensated). */
    val offset: Double,
    /** Whether the device clock was set. */
    val applied: Boolean,
)

// endregion

// region Config

private val localIds = AtomicLong(1)

@Serializable
data class CameraConfig(
    // Device-owned (ignored by the device on write).
    val deviceId: String? = null,
    val configRev: Int? = null,
    val name: String? = null,
    val cameraType: String,
    /** All durations are seconds. */
    val wakeUpTimeout: Double,
    val startBefore: Double,
    val endAfter: Double,
    val logFile: String,
    val buttonGpioPort: Int? = null,
    val generic: Generic = Generic(),
    val zcam: Zcam = Zcam(),
    val attempts: List<Attempt> = emptyList(),
) {
    @Serializable
    data class Generic(val releaseGpioPin: Int? = null, val focusGpioPin: Int? = null)

    @Serializable
    data class Zcam(val cameraIp: String? = null)

    @Serializable
    data class Attempt(
        val name: String,
        /** ISO 8601 UTC. */
        val time: String,
        /** Local identity for the UI; never sent to the device. */
        @Transient val localId: Long = localIds.getAndIncrement(),
    ) {
        val instant: Instant get() = DeviceDate.parse(time) ?: Instant.now()

        fun withInstant(instant: Instant) = copy(time = DeviceDate.string(instant))

        companion object {
            fun create(name: String, time: Instant) = Attempt(name, DeviceDate.string(time))
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

@Serializable
data class JobEvent(val name: String, val step: String, val at: String)

@Serializable
data class TestEvent(val step: String, val ok: Boolean? = null, val error: String? = null)

@Serializable
internal data class ConfigEvent(val configRev: Int)

sealed interface DeviceEvent {
    data class Job(val event: JobEvent) : DeviceEvent
    data class Config(val revision: Int) : DeviceEvent
    data class Test(val event: TestEvent) : DeviceEvent
    data class Log(val entry: LogEntry) : DeviceEvent
}

@Serializable
internal data class ErrorBody(
    val code: String,
    @SerialName("msg") val message: String,
    val field: String? = null,
    val retryAfter: Int? = null,
) {
    fun asError() = DeviceError(code, message, field, retryAfter)
}

// endregion
