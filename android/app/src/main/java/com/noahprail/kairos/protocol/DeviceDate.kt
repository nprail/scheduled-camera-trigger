package com.noahprail.kairos.protocol

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** ISO 8601 UTC with milliseconds, as used on the wire. */
object DeviceDate {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Accepts the timestamp with or without a fractional part. */
    fun parse(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: Exception) {
        null
    }

    fun string(instant: Instant): String = formatter.format(instant)
}
