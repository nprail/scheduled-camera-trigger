package com.noahprail.camtrigger.protocol

/** An error from the device or the link, with a message fit to show the user. */
class DeviceError(
    val code: String,
    val rawMessage: String,
    val field: String? = null,
    val retryAfter: Int? = null,
) : Exception(friendlyMessage(code, rawMessage, field, retryAfter)) {

    override fun equals(other: Any?): Boolean =
        other is DeviceError && other.code == code && other.rawMessage == rawMessage &&
            other.field == field && other.retryAfter == retryAfter

    override fun hashCode(): Int = code.hashCode() * 31 + rawMessage.hashCode()

    companion object {
        private fun friendlyMessage(code: String, raw: String, field: String?, retryAfter: Int?): String = when (code) {
            "busy" -> "The device is busy right now. Try again after the current recording or test."
            "unauthorized" -> "The device rejected the pairing key. Re-pair using the device's button."
            "locked_out" ->
                if (retryAfter != null) "Too many failed attempts. Try again in $retryAfter s."
                else "Too many failed attempts."
            "pairing_closed" -> "Press the button on the device, then try again within 2 minutes."
            "invalid_config" -> if (field != null) "$field: $raw" else raw
            else -> raw
        }

        val disconnected = DeviceError("disconnected", "The device disconnected.")
        val timeout = DeviceError("timeout", "The device did not respond in time.")
    }
}

/** The text to show for any thrown error. */
fun describe(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error.toString()
