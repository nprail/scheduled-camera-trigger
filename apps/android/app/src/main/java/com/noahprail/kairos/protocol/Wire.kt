package com.noahprail.kairos.protocol

import java.time.Instant

// The binary messages of protocol v2 (docs/ble-protocol.md, section 4). The app encodes requests and
// decodes responses and events. All integers are little-endian.

internal enum class Op(val code: Int) {
    HELLO(0x01),
    AUTH(0x02),
    AUTH_PAIR(0x03),
    TIME_GET(0x04),
    TIME_SET(0x05),
    CONFIG_GET(0x07),
    CONFIG_SET(0x08),
    JOBS_GET(0x09),
    CAMERA_WAKE(0x10),
    CAMERA_START(0x11),
    CAMERA_STOP(0x12),
    CAMERA_SLEEP(0x13),
    TEST_RUN(0x14),
    LOGS_GET(0x20),
    LOGS_SUBSCRIBE(0x21),
    FW_BEGIN(0x30),
    FW_END(0x31),
    FW_ABORT(0x32),
}

internal class ByteWriter {
    private val out = java.io.ByteArrayOutputStream()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
    fun u32(v: Long) = apply { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }
    fun u64(v: Long) = apply { u32(v and 0xFFFFFFFFL); u32((v ushr 32) and 0xFFFFFFFFL) }
    fun bytes(b: ByteArray) = apply { out.write(b) }

    /** A `u8` length followed by the UTF-8 bytes. Callers check the limit. */
    fun string(s: String) = apply {
        val b = s.toByteArray(Charsets.UTF_8)
        val n = minOf(b.size, 255)
        u8(n)
        out.write(b, 0, n)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads past the end set [ok] to false and return zero, so a decoder can check once at the end. */
internal class ByteReader(private val bytes: ByteArray) {
    private var pos = 0
    var ok = true
        private set

    val remaining get() = bytes.size - pos

    private fun need(n: Int): Boolean {
        if (!ok || pos + n > bytes.size) {
            ok = false
            return false
        }
        return true
    }

    fun u8(): Int = if (need(1)) bytes[pos++].toInt() and 0xFF else 0
    fun u16(): Int = u8() or (u8() shl 8)
    fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
    fun i32(): Int = u32().toInt()
    fun u64(): Long = u32() or (u32() shl 32)
    fun bytes(n: Int): ByteArray = if (need(n)) bytes.copyOfRange(pos, pos + n).also { pos += n } else ByteArray(n)
    fun string(): String {
        val n = u8()
        return if (need(n)) String(bytes, pos, n, Charsets.UTF_8).also { pos += n } else ""
    }
}

internal object Wire {
    const val RESPONSE = 0x80
    const val EVENT = 0x81
    const val CONFIG_LAYOUT = 1
    const val MAX_ATTEMPTS = 30
    const val MAX_NAME_BYTES = 32

    /** `[op][id][body]` */
    fun request(op: Op, id: Int, body: ByteArray = ByteArray(0)): ByteArray =
        ByteWriter().u8(op.code).u8(id).bytes(body).toByteArray()

    sealed interface Message {
        class Response(val id: Int, val status: Int, val body: ByteArray) : Message
        class Event(val code: Int, val body: ByteArray) : Message
    }

    fun parse(data: ByteArray): Message? {
        if (data.isEmpty()) return null
        val first = data[0].toInt() and 0xFF
        if (first == EVENT && data.size >= 2) return Message.Event(data[1].toInt() and 0xFF, data.copyOfRange(2, data.size))
        if (first == RESPONSE && data.size >= 3) {
            return Message.Response(data[1].toInt() and 0xFF, data[2].toInt() and 0xFF, data.copyOfRange(3, data.size))
        }
        return null
    }

    // region Config

    private fun invalid(field: String, message: String) = DeviceError("invalid_config", message, field)

    fun encodeConfig(c: CameraConfig): ByteArray {
        val type = CameraConfig.cameraTypes.indexOf(c.cameraType)
        if (type < 0) throw invalid("cameraType", "is not a known camera type")
        val name = c.name ?: ""
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) throw invalid("name", "must be at most $MAX_NAME_BYTES bytes")
        if (c.attempts.size > MAX_ATTEMPTS) throw invalid("attempts", "has too many attempts (at most $MAX_ATTEMPTS)")

        fun seconds(v: Double) = Math.round(v).coerceIn(0, 65535).toInt()

        val w = ByteWriter()
        w.u8(CONFIG_LAYOUT).u8(type)
        w.u16(seconds(c.wakeUpTimeout)).u16(seconds(c.startBefore)).u16(seconds(c.endAfter))
        for (octet in ipBytes(c.zcam.cameraIp)) w.u8(octet)
        w.string(name)
        w.u8(c.attempts.size)
        c.attempts.forEachIndexed { i, a ->
            if (a.name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
                throw invalid("attempts[$i].name", "must be at most $MAX_NAME_BYTES bytes")
            }
            w.u32(a.time).string(a.name)
        }
        return w.toByteArray()
    }

    /** `192.168.1.50` to its 4 bytes. Empty means none (all zeros). */
    fun ipBytes(text: String?): List<Int> {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return listOf(0, 0, 0, 0)
        val parts = trimmed.split(".")
        val octets = parts.mapNotNull { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p.all(Char::isDigit) } }
        if (parts.size != 4 || octets.size != 4) throw invalid("zcam.cameraIp", "must be an IPv4 address like 192.168.1.50")
        return octets
    }

    fun decodeConfig(data: ByteArray): CameraConfig {
        val r = ByteReader(data)
        val unreadable = DeviceError("bad_request", "The device sent a config this app does not understand.")
        if (r.u8() != CONFIG_LAYOUT) throw unreadable
        val type = r.u8()
        val wake = r.u16()
        val start = r.u16()
        val end = r.u16()
        val ip = listOf(r.u8(), r.u8(), r.u8(), r.u8())
        val name = r.string()
        val count = r.u8()
        val attempts = mutableListOf<CameraConfig.Attempt>()
        repeat(count) {
            val time = r.u32()
            attempts += CameraConfig.Attempt(r.string(), time)
        }
        if (!r.ok || type >= CameraConfig.cameraTypes.size || count > MAX_ATTEMPTS) throw unreadable
        return CameraConfig(
            name = name.ifEmpty { null },
            cameraType = CameraConfig.cameraTypes[type],
            wakeUpTimeout = wake.toDouble(),
            startBefore = start.toDouble(),
            endAfter = end.toDouble(),
            zcam = CameraConfig.Zcam(if (ip.all { it == 0 }) null else ip.joinToString(".")),
            attempts = attempts,
        )
    }

    // endregion

    // region Errors

    fun deviceError(status: Int, body: ByteArray): DeviceError {
        val r = ByteReader(body)
        return when (status) {
            1 -> DeviceError("bad_request", "The device could not understand the request.")
            2 -> DeviceError("unauthorized", "Not authorized.")
            3 -> DeviceError("locked_out", "Too many failed attempts.", retryAfter = if (r.remaining >= 2) r.u16() else null)
            4 -> DeviceError("not_supported", "The device does not support that.")
            5 -> DeviceError("busy", "The device is busy.")
            6 ->
                if (r.remaining >= 3) {
                    val field = r.u8()
                    val index = r.u8()
                    val reason = r.u8()
                    DeviceError("invalid_config", reasonText(reason), fieldName(field, index))
                } else {
                    DeviceError("invalid_config", "The config is not valid.")
                }
            7 -> DeviceError("too_large", "The request is too large for the device.")
            8 -> DeviceError("pairing_closed", "Pairing is closed.")
            10 -> DeviceError("bad_image", badImageText(if (r.remaining >= 1) r.u8() else 0))
            else -> {
                val detail = if (r.remaining >= 1) r.string() else ""
                DeviceError("internal", detail.ifEmpty { "The device had an internal error." })
            }
        }
    }

    /** The `bad_image` reasons (docs/ble-protocol.md, Errors). */
    fun badImageText(id: Int): String = when (id) {
        1 -> "The firmware file is too small to be a Kairos update."
        2 -> "That isn't a Kairos application update. Use the file ending in -app.bin, not -factory.bin."
        3 -> "That firmware is for a different chip."
        4 -> "The firmware didn't arrive intact. Try again."
        5 -> "The device could not write the update. Try again."
        6 -> "That firmware isn't signed, and this device only accepts firmware signed by Kairos."
        7 -> "The firmware's signature isn't valid for this device."
        8 -> "The device did not receive the whole update. Try again."
        9 -> "The update stopped because the transfer stalled or a scheduled event came close."
        else -> "The device rejected the firmware."
    }

    /** The `invalid_config` field ids, as the names the editor uses. */
    fun fieldName(id: Int, index: Int): String = when (id) {
        1 -> "name"
        2 -> "cameraType"
        3 -> "wakeUpTimeout"
        4 -> "startBefore"
        5 -> "endAfter"
        9 -> "zcam.cameraIp"
        10 -> "attempts"
        11 -> "attempts[$index].name"
        12 -> "attempts[$index].time"
        else -> "config"
    }

    fun reasonText(id: Int): String = when (id) {
        1 -> "is required"
        2 -> "is out of range"
        3 -> "is not valid"
        4 -> "must be in the future"
        5 -> "duplicates another attempt"
        6 -> "is too soon: the camera wake-up time has already passed"
        7 -> "has too many attempts (at most $MAX_ATTEMPTS)"
        else -> "is not valid"
    }

    // endregion

    // region Responses and events

    private val malformed get() = DeviceError("internal", "Malformed response.")

    fun hello(body: ByteArray): HelloResponse {
        val r = ByteReader(body)
        val v = r.u8()
        val flags = r.u8()
        val nonce = r.bytes(16)
        val now = r.u64()
        val max = r.u16()
        if (!r.ok) throw malformed
        return HelloResponse(v, flags and 1 != 0, nonce, now, max)
    }

    fun timeGet(body: ByteArray): Long {
        val r = ByteReader(body)
        val ms = r.u64()
        if (!r.ok) throw malformed
        return ms
    }

    fun jobStates(body: ByteArray): List<String> {
        val r = ByteReader(body)
        val n = r.u8()
        val names = listOf("pending", "active", "complete", "missed")
        val states = List(n) { names.getOrElse(r.u8()) { "pending" } }
        if (!r.ok) throw malformed
        return states
    }

    private fun logLine(r: ByteReader): LogEntry {
        val seq = r.u32().toInt()
        val ts = r.u32()
        val ns = r.string()
        return LogEntry(seq, ts, ns, r.string())
    }

    fun logs(body: ByteArray): DeviceClient.LogPage {
        val r = ByteReader(body)
        val last = r.u32().toInt()
        val more = r.u8() != 0
        val n = r.u8()
        val entries = List(n) { logLine(r) }
        if (!r.ok) throw malformed
        return DeviceClient.LogPage(entries, last, more)
    }

    fun decodeEvent(code: Int, body: ByteArray): DeviceEvent? {
        val r = ByteReader(body)
        return when (code) {
            0x01 -> {
                val attempt = r.u8()
                val step = r.u8()
                val at = r.u32()
                val steps = listOf("wake", "start", "stop", "complete", "missed")
                if (r.ok && step < steps.size) DeviceEvent.Job(JobEvent(attempt, steps[step], Instant.ofEpochSecond(at))) else null
            }
            0x02 -> r.u32().let { if (r.ok) DeviceEvent.Config(it.toInt()) else null }
            0x03 -> {
                val step = r.u8()
                val ok = r.u8() != 0
                val error = if (r.remaining > 0) r.string() else ""
                val steps = listOf("wake", "start", "stop", "sleep", "done")
                if (r.ok && step < steps.size) DeviceEvent.Test(TestEvent(steps[step], ok, error.ifEmpty { null })) else null
            }
            0x04 -> logLine(r).let { if (r.ok) DeviceEvent.Log(it) else null }
            else -> null
        }
    }

    /** The `fw` event (0x05). It has its own channel: the uploader waits for it. */
    fun firmwareEvent(body: ByteArray): FirmwareEvent? {
        val r = ByteReader(body)
        val state = r.u8()
        val offset = r.u32().toInt()
        val reason = if (r.remaining >= 1) r.u8() else 0
        if (!r.ok) return null
        val s = FirmwareEvent.State.entries.getOrNull(state) ?: return null
        return FirmwareEvent(s, offset, reason)
    }

    fun firmwareBegin(body: ByteArray): Pair<Int, Int> {
        val r = ByteReader(body)
        val offset = r.u32().toInt()
        val window = r.u16()
        if (!r.ok) throw malformed
        return offset to window
    }

    fun firmwareEnd(body: ByteArray): Int {
        val r = ByteReader(body)
        val ms = r.u16()
        if (!r.ok) throw malformed
        return ms
    }

    // endregion
}
