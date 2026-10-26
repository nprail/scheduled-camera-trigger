package com.noahprail.kairos.protocol

/**
 * Message framing from docs/ble-protocol.md §3.
 *
 *     First fragment:        [0x80 | idx] [u16 BE total length] [payload…]
 *     Continuation fragment: [idx]                              [payload…]
 */
object Framing {
    const val MAX_MESSAGE = 4096

    /** Split [payload] into fragments of at most [maxFragment] bytes each. */
    fun fragments(payload: ByteArray, maxFragment: Int): List<ByteArray> {
        require(maxFragment >= 4) { "maxFragment too small" }
        require(payload.size <= 0xFFFF) { "payload too long" }

        val out = mutableListOf<ByteArray>()
        var idx = 0
        var offset = 0

        do {
            val first = out.isEmpty()
            val headerSize = if (first) 3 else 1
            val take = minOf(payload.size - offset, maxFragment - headerSize)

            val frag = ByteArray(headerSize + take)
            frag[0] = ((if (first) 0x80 else 0x00) or (idx and 0x7F)).toByte()
            if (first) {
                frag[1] = ((payload.size shr 8) and 0xFF).toByte()
                frag[2] = (payload.size and 0xFF).toByte()
            }
            payload.copyInto(frag, headerSize, offset, offset + take)
            out += frag

            offset += take
            idx = (idx + 1) and 0x7F
        } while (offset < payload.size)

        return out
    }
}

enum class FramingError { BAD_FRAME, TOO_LARGE }

/** Reassembles notification fragments into whole messages. */
class Reassembler(private val maxMessage: Int = Framing.MAX_MESSAGE) {
    sealed interface Result {
        data object NeedMore : Result
        class Message(val data: ByteArray) : Result
        data class Error(val error: FramingError) : Result
    }

    private var total = 0
    private var buffer = java.io.ByteArrayOutputStream()
    private var nextIdx = 0
    private var active = false
    private var discarding = false

    val isPending: Boolean get() = active

    fun reset() {
        total = 0
        buffer = java.io.ByteArrayOutputStream()
        nextIdx = 0
        active = false
        discarding = false
    }

    fun push(data: ByteArray): Result {
        if (data.isEmpty()) return Result.Error(FramingError.BAD_FRAME)

        val header = data[0].toInt() and 0xFF
        val isFirst = (header and 0x80) != 0
        val idx = header and 0x7F

        if (isFirst) {
            reset()
            if (data.size < 3 || idx != 0) return Result.Error(FramingError.BAD_FRAME)

            val length = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
            if (length == 0) return Result.Error(FramingError.BAD_FRAME)
            if (length > maxMessage) {
                discarding = true
                return Result.Error(FramingError.TOO_LARGE)
            }

            total = length
            active = true
            nextIdx = 1
            return append(data, 3)
        }

        if (discarding) return Result.NeedMore
        if (!active || idx != nextIdx) {
            reset()
            return Result.Error(FramingError.BAD_FRAME)
        }

        nextIdx = (nextIdx + 1) and 0x7F
        return append(data, 1)
    }

    private fun append(data: ByteArray, from: Int): Result {
        buffer.write(data, from, data.size - from)

        if (buffer.size() > total) {
            reset()
            return Result.Error(FramingError.BAD_FRAME)
        }
        if (buffer.size() == total) {
            val message = buffer.toByteArray()
            reset()
            return Result.Message(message)
        }
        return Result.NeedMore
    }
}
