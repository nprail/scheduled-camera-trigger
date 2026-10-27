package com.noahprail.kairos.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Sends one firmware image to a device (docs/ble-protocol.md, fw.*): `fw.begin`, the bytes on the Firmware
 * characteristic within the device's window, `fw.end`. The device reports progress; a lost write shows up as
 * an event that does not move, and the uploader sends again from where the device is.
 */
class FirmwareUploader(
    private val client: DeviceClient,
    private val transport: DeviceTransport,
    /** How long to wait for the device to report before sending again from its offset. */
    private val stallTimeoutMs: Long = 3000,
    private val maxStalls: Int = 4,
) {
    class Progress(
        /** Bytes the device has confirmed. */
        val acknowledged: Int,
        val total: Int,
    ) {
        val fraction: Double get() = if (total == 0) 0.0 else acknowledged.toDouble() / total
    }

    /**
     * Send [image] and tell the device to install it. Returns the delay, in milliseconds, before it restarts.
     * [signature] is the 64-byte signature, or empty for an unsigned (developer) image.
     */
    suspend fun upload(image: ByteArray, signature: ByteArray, progress: (Progress) -> Unit): Int {
        val total = image.size
        val sha = MessageDigest.getInstance("SHA-256").digest(image)
        val (beginOffset, beginWindow) = client.firmwareBegin(total, sha, signature)
        client.dropFirmwareEvents()

        val window = maxOf(beginWindow, 1024)
        val payload = maxOf(transport.maxWriteLength - 4, 16)
        var acked = minOf(beginOffset, total)
        var sent = acked
        var stalls = 0
        var lastRewind = 0L
        progress(Progress(acked, total))

        fun handle(event: FirmwareEvent) {
            if (event.state == FirmwareEvent.State.FAILED) throw Wire.deviceError(10, byteArrayOf(event.reason.toByte()))
            if (event.offset > acked) {
                acked = minOf(event.offset, total)
                stalls = 0
                progress(Progress(acked, total))
            } else if (event.offset == acked && sent > acked && System.nanoTime() - lastRewind > 300_000_000L) {
                // the device is still waiting for byte `acked`: what came after it was lost
                sent = acked
                lastRewind = System.nanoTime()
            }
        }

        try {
            while (acked < total) {
                while (true) handle(client.pollFirmwareEvent() ?: break)
                if (acked >= total) break

                if (sent < total && sent - acked < window) {
                    val end = minOf(sent + payload, total)
                    val packet = ByteWriter().u32(sent.toLong()).bytes(image.copyOfRange(sent, end)).toByteArray()
                    transport.writeFirmware(packet)
                    sent = end
                    continue
                }

                val event = client.awaitFirmwareEvent(stallTimeoutMs)
                if (event != null) {
                    handle(event)
                } else {
                    if (client.isLinkClosed) throw DeviceError.disconnected
                    stalls += 1
                    if (stalls >= maxStalls) throw DeviceError.timeout
                    sent = acked // nothing came back: send the unconfirmed part again
                    lastRewind = System.nanoTime()
                }
            }
            return client.firmwareEnd()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { client.firmwareAbort() }
            throw e
        } catch (e: DeviceError) {
            when (e.code) {
                // the device has already dropped the transfer / keeps a partial one for two minutes, so the same image can resume
                "bad_image", "disconnected", "timeout" -> throw e
                else -> {
                    client.firmwareAbort()
                    throw e
                }
            }
        }
    }
}
