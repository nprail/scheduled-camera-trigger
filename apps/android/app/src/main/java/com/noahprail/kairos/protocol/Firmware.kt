package com.noahprail.kairos.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

// Firmware images and release manifests, on the phone side (docs/ble-protocol.md, section 7 and fw.*).
// Everything here is checked against the same vectors as the Node reference (protocol/test-vectors.json).

/** The chips a Kairos device can run on. */
enum class Chip(val id: String, internal val headerId: Int, val title: String) {
    ESP32S3("esp32s3", 9, "ESP32-S3"),
    ESP32C3("esp32c3", 5, "ESP32-C3");

    companion object {
        /** Device Info `chip`: 1 ESP32-S3, 2 ESP32-C3. */
        fun fromDeviceValue(v: Int): Chip? = when (v) {
            1 -> ESP32S3
            2 -> ESP32C3
            else -> null
        }

        fun fromId(id: String): Chip? = entries.firstOrNull { it.id == id }
    }
}

/** What a firmware file turned out to be. */
sealed interface FirmwareCheck {
    /** An application image for this chip. [version] is its tag, or null if it has none. */
    data class Ok(val version: String?) : FirmwareCheck
    data object NotImage : FirmwareCheck
    data object WrongChip : FirmwareCheck

    /** An image, but not an application image (for example the factory image). */
    data object NotApp : FirmwareCheck

    val message: String?
        get() = when (this) {
            is Ok -> null
            NotImage -> "That file isn't Kairos firmware."
            WrongChip -> "That firmware is for a different chip."
            NotApp -> "That's a factory image. Use the file ending in -app.bin to update a device."
        }
}

object FirmwareImage {
    private val tagMagic = "KAIROSFW".toByteArray(Charsets.US_ASCII)
    private const val TAG_LENGTH = 8 + 1 + 1 + 32

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

    private fun ByteArray.indexOfTag(from: Int): Int {
        var i = from
        val last = size - tagMagic.size
        while (i <= last) {
            var match = true
            for (j in tagMagic.indices) {
                if (this[i + j] != tagMagic[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
            i++
        }
        return -1
    }

    /** Check a firmware file for [chip], the same way the reference does. */
    fun check(data: ByteArray, chip: Chip): FirmwareCheck {
        if (data.size < 64 || data.u8(0) != 0xE9) return FirmwareCheck.NotImage
        val headerChip = data.u8(12) or (data.u8(13) shl 8)
        if (headerChip != chip.headerId) return FirmwareCheck.WrongChip
        val appDesc = data.u8(32).toLong() or (data.u8(33).toLong() shl 8) or (data.u8(34).toLong() shl 16) or (data.u8(35).toLong() shl 24)
        if (appDesc != 0xABCD5432L) return FirmwareCheck.NotApp

        var at = data.indexOfTag(0)
        while (at >= 0 && at + TAG_LENGTH <= data.size) {
            if (data.u8(at + 8) == 1) {
                val tagChip = Chip.fromDeviceValue(data.u8(at + 9))
                if (tagChip != null && tagChip != chip) return FirmwareCheck.WrongChip
                var end = at + 10
                while (end < at + TAG_LENGTH && data[end].toInt() != 0) end++
                val version = String(data, at + 10, end - (at + 10), Charsets.US_ASCII)
                return FirmwareCheck.Ok(version.ifEmpty { null })
            }
            at = data.indexOfTag(at + 1)
        }
        return FirmwareCheck.Ok(null)
    }

    /** A signature file: the 64 raw bytes, or the same as 128 hex characters. */
    fun signature(data: ByteArray): ByteArray? {
        if (data.size == 64) return data
        val text = String(data, Charsets.US_ASCII).trim()
        if (text.length != 128 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return hexToBytes(text)
    }
}

fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

// region Versions

object FirmwareVersion {
    private class Parsed(val core: List<Int>, val pre: List<String>)

    private val pattern = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

    private fun parse(text: String): Parsed? {
        val m = pattern.matchEntire(text.trim()) ?: return null
        val core = (1..3).map { m.groupValues[it].toIntOrNull() ?: return null }
        val pre = m.groupValues[4].let { if (it.isEmpty()) emptyList() else it.split(".") }
        return Parsed(core, pre)
    }

    /** Whether [text] is a release version such as `1.3.0` or `1.3.0-beta.1`. */
    fun isValid(text: String): Boolean = parse(text) != null

    /** -1, 0 or 1; null if either side is not a semantic version (a development build, "dev"). */
    fun compare(a: String, b: String): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        for (i in 0 until 3) if (x.core[i] != y.core[i]) return if (x.core[i] < y.core[i]) -1 else 1
        if (x.pre.isEmpty() || y.pre.isEmpty()) return if (x.pre.size == y.pre.size) 0 else if (x.pre.isEmpty()) 1 else -1
        for (i in 0 until minOf(x.pre.size, y.pre.size)) {
            val p = x.pre[i]
            val q = y.pre[i]
            if (p == q) continue
            val pn = p.all { it in '0'..'9' }
            val qn = q.all { it in '0'..'9' }
            if (pn && qn) return if (p.toBigInteger() < q.toBigInteger()) -1 else 1
            if (pn != qn) return if (pn) -1 else 1
            return if (p < q) -1 else 1
        }
        return if (x.pre.size == y.pre.size) 0 else if (x.pre.size < y.pre.size) -1 else 1
    }
}

// endregion

// region Release manifest

/** A release's `manifest.json` (see docs/ota-plan.md). */
class ReleaseManifest(
    val version: String?,
    val protocolVersion: Int?,
    /** The oldest firmware that may update directly to this release. */
    val minFrom: String?,
    val notes: String?,
    val images: Map<String, Image>?,
) {
    class Image(val file: String?, val size: Int?, val sha256: String?, val sig: String?) {
        /** All four fields are present and well formed. */
        val isValid: Boolean
            get() = file != null && size != null && size > 0 &&
                sha256 != null && sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' } &&
                sig != null && sig.length == 128 && sig.all { it in '0'..'9' || it in 'a'..'f' }

        val signature: ByteArray? get() = sig?.takeIf { it.length == 128 }?.let { hexToBytes(it) }
    }

    enum class Status(val id: String) {
        INVALID("invalid"), UNAVAILABLE("unavailable"), UP_TO_DATE("up_to_date"), NEWER_INSTALLED("newer_installed"),
        UNKNOWN_CURRENT("unknown_current"), APP_TOO_OLD("app_too_old"), NEEDS_USB("needs_usb"), AVAILABLE("available"),
    }

    class Assessment(val status: Status, val image: Image?)

    /** What this manifest means for a device running [deviceFw]. */
    fun assess(chip: Chip, deviceFw: String, appProtocol: Int): Assessment {
        if (version == null || !FirmwareVersion.isValid(version) || images == null || protocolVersion == null) {
            return Assessment(Status.INVALID, null)
        }
        val image = images[chip.id] ?: return Assessment(Status.UNAVAILABLE, null)
        if (!image.isValid) return Assessment(Status.INVALID, null)

        val cmp = FirmwareVersion.compare(version, deviceFw) ?: return Assessment(Status.UNKNOWN_CURRENT, image)
        if (cmp == 0) return Assessment(Status.UP_TO_DATE, image)
        if (cmp < 0) return Assessment(Status.NEWER_INSTALLED, image)
        if (protocolVersion > appProtocol) return Assessment(Status.APP_TOO_OLD, image)
        if (minFrom != null && FirmwareVersion.compare(deviceFw, minFrom) == -1) return Assessment(Status.NEEDS_USB, image)
        return Assessment(Status.AVAILABLE, image)
    }

    companion object {
        private fun JsonElement?.intOrNull(): Int? =
            (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it == Math.floor(it) }?.toInt()

        private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /** Null if [data] is not a JSON object. */
        fun parse(data: ByteArray): ReleaseManifest? {
            val json = try {
                Json.parseToJsonElement(String(data, Charsets.UTF_8)) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val images = (json["images"] as? JsonObject)?.mapValues { (_, value) ->
                val d = (value as? JsonObject) ?: JsonObject(emptyMap())
                Image(d["file"].stringOrNull(), d["size"].intOrNull(), d["sha256"].stringOrNull(), d["sig"].stringOrNull())
            }
            return ReleaseManifest(
                version = json["version"].stringOrNull(),
                protocolVersion = json["protocol"].intOrNull(),
                minFrom = json["minFrom"].stringOrNull(),
                notes = json["notes"].stringOrNull(),
                images = images,
            )
        }
    }
}

// endregion
