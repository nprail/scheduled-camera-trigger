package com.noahprail.kairos.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Instant

/** The protocol version this app speaks. */
const val PROTOCOL_VERSION = 2

/**
 * What the client needs from a BLE link. Implemented over Android's GATT API
 * by `BleDeviceConnection`, and by fakes in tests.
 */
interface DeviceTransport {
    /** Largest single write (ATT payload) the link accepts. */
    val maxWriteLength: Int

    /** Write one fragment to the Command characteristic, with response. */
    suspend fun write(data: ByteArray)

    /** Fragments notified on the Response characteristic. Completes on disconnect. */
    val notifications: Flow<ByteArray>

    /** Write one packet to the Firmware characteristic, without response. Returns when the link took it. */
    suspend fun writeFirmware(data: ByteArray) {
        throw DeviceError("not_supported", "The device does not support that.")
    }
}

enum class CameraAction {
    WAKE, START, STOP, SLEEP;

    internal val op: Op
        get() = when (this) {
            WAKE -> Op.CAMERA_WAKE
            START -> Op.CAMERA_START
            STOP -> Op.CAMERA_STOP
            SLEEP -> Op.CAMERA_SLEEP
        }
}

/**
 * Speaks the request/response protocol (docs/ble-protocol.md, section 4) over a
 * [DeviceTransport]. One request is in flight at a time; extra callers wait.
 *
 * Call it from a single dispatcher (the main one in the app).
 */
class DeviceClient(private val transport: DeviceTransport, scope: CoroutineScope) {
    /** Unsolicited events (job steps, test progress, log lines, config changes). */
    private val eventFlow = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 512)
    val events: Flow<DeviceEvent> = eventFlow.asSharedFlow()

    private val reassembler = Reassembler()
    private var nextId = 1
    private val pending = mutableMapOf<Int, CompletableDeferred<ByteArray>>()
    private var inFlight: Int? = null
    private val mutex = Mutex()
    private val readJob: kotlinx.coroutines.Job
    private val firmwareChannel = kotlinx.coroutines.channels.Channel<FirmwareEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    @Volatile
    internal var isLinkClosed = false
        private set

    var maxMessage = Framing.MAX_MESSAGE
        private set

    init {
        readJob = scope.launch {
            try {
                transport.notifications.collect { receive(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            linkClosed()
        }
    }

    fun close() {
        readJob.cancel()
    }

    // region Receiving

    private fun receive(fragment: ByteArray) {
        when (val result = reassembler.push(fragment)) {
            Reassembler.Result.NeedMore -> Unit
            is Reassembler.Result.Message -> dispatch(result.data)
            is Reassembler.Result.Error ->
                // Framing errors carry no id; only one request is ever in flight.
                inFlight?.let {
                    resolve(it, Result.failure(DeviceError("bad_request", "Framing error: ${result.error}")))
                }
        }
    }

    private fun dispatch(data: ByteArray) {
        when (val message = Wire.parse(data)) {
            is Wire.Message.Event ->
                if (message.code == 0x05) {
                    Wire.firmwareEvent(message.body)?.let { firmwareChannel.trySend(it) }
                } else {
                    Wire.decodeEvent(message.code, message.body)?.let { eventFlow.tryEmit(it) }
                }
            is Wire.Message.Response ->
                if (message.id == 0 && message.status != 0) {
                    // an error the device could not attribute to a request (id 0)
                    inFlight?.let { resolve(it, Result.success(data)) }
                } else {
                    resolve(message.id, Result.success(data))
                }
            null -> Unit
        }
    }

    private fun resolve(id: Int, result: Result<ByteArray>) {
        val deferred = pending.remove(id) ?: return
        if (inFlight == id) inFlight = null
        result.onSuccess { deferred.complete(it) }.onFailure { deferred.completeExceptionally(it) }
    }

    private fun linkClosed() {
        isLinkClosed = true
        for (id in pending.keys.toList()) resolve(id, Result.failure(DeviceError.disconnected))
        firmwareChannel.close()
    }

    /** The next `fw` event already received, if any. */
    internal fun pollFirmwareEvent(): FirmwareEvent? = firmwareChannel.tryReceive().getOrNull()

    /** Wait for the next `fw` event. Null on timeout or when the link closes. */
    internal suspend fun awaitFirmwareEvent(timeoutMs: Long): FirmwareEvent? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { firmwareChannel.receiveCatching().getOrNull() }

    internal fun dropFirmwareEvents() {
        while (firmwareChannel.tryReceive().isSuccess) Unit
    }

    // endregion

    // region Sending

    /** Send one request and return the body of its response. Throws the device's error for a non-zero status. */
    internal suspend fun call(op: Op, body: ByteArray = ByteArray(0), timeoutMs: Long = 10_000): ByteArray = mutex.withLock {
        val id = nextId
        nextId = if (nextId >= 255) 1 else nextId + 1 // ids are one byte; 0 is reserved for unattributable errors

        val message = Wire.request(op, id, body)
        if (message.size > minOf(maxMessage, Framing.MAX_MESSAGE)) {
            throw DeviceError("too_large", "Request is too large for the device.")
        }
        val frames = Framing.fragments(message, maxOf(transport.maxWriteLength, 20))

        val deferred = CompletableDeferred<ByteArray>()
        pending[id] = deferred
        inFlight = id

        val response = try {
            withTimeout(timeoutMs) {
                for (frame in frames) transport.write(frame)
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw DeviceError.timeout
        } finally {
            pending.remove(id)
            if (inFlight == id) inFlight = null
        }

        val parsed = Wire.parse(response) as? Wire.Message.Response ?: throw DeviceError("internal", "Malformed response.")
        if (parsed.status != 0) throw Wire.deviceError(parsed.status, parsed.body)
        parsed.body
    }

    // endregion

    // region Session

    suspend fun hello(): HelloResponse {
        val response = Wire.hello(call(Op.HELLO))
        if (response.v != PROTOCOL_VERSION) {
            throw DeviceError(
                "unsupported",
                "This device speaks protocol v${response.v}; this app supports v$PROTOCOL_VERSION. Update the app.",
            )
        }
        maxMessage = response.maxMessage
        return response
    }

    /** Prove knowledge of [secret] and verify the device does too. */
    suspend fun authenticate(secret: ByteArray, hello: HelloResponse) {
        val clientNonce = AuthProof.randomBytes(16)
        val proof = AuthProof.client(secret, hello.nonce, clientNonce)
        val reply = call(Op.AUTH, ByteWriter().bytes(clientNonce).bytes(proof).toByteArray())

        val expected = AuthProof.server(secret, hello.nonce, clientNonce)
        if (reply.size < 32 || !expected.contentEquals(reply.copyOf(32))) {
            throw DeviceError("unauthorized", "The device could not prove it holds the pairing key.")
        }
    }

    /** Store a new shared secret on the device. Only works during the pairing window. */
    suspend fun pair(secret: ByteArray) {
        call(Op.AUTH_PAIR, secret)
    }

    // endregion

    // region Operations

    suspend fun config(): CameraConfig = Wire.decodeConfig(call(Op.CONFIG_GET))

    /** Replace the device config. Returns the new `configRev`. */
    suspend fun setConfig(config: CameraConfig): Int =
        ByteReader(call(Op.CONFIG_SET, Wire.encodeConfig(config))).u32().toInt()

    /** The state of each attempt in [config], in order. The launch times come from the config. */
    suspend fun jobs(config: CameraConfig): List<Job> {
        val states = Wire.jobStates(call(Op.JOBS_GET))
        return config.attempts.zip(states).mapIndexed { index, (attempt, state) ->
            Job(index, attempt.name, attempt.instant, state)
        }
    }

    suspend fun camera(action: CameraAction) {
        call(action.op, timeoutMs = 20_000)
    }

    suspend fun runTest() {
        call(Op.TEST_RUN)
    }

    data class LogPage(val entries: List<LogEntry>, val last: Int, val more: Boolean)

    suspend fun logs(after: Int = 0, limit: Int = 20): LogPage =
        Wire.logs(call(Op.LOGS_GET, ByteWriter().u32(maxOf(0, after).toLong()).u8(limit.coerceIn(1, 100)).toByteArray()))

    /** Start (or stop) streaming log lines as log events, replaying anything after [after]. */
    suspend fun subscribeLogs(enabled: Boolean, after: Int = 0) {
        call(Op.LOGS_SUBSCRIBE, ByteWriter().u8(if (enabled) 1 else 0).u32(maxOf(0, after).toLong()).toByteArray())
    }

    // region Firmware update

    /** Declare an image. Returns where to start sending (a resumed transfer starts past 0) and the window. */
    internal suspend fun firmwareBegin(size: Int, sha256: ByteArray, signature: ByteArray): Pair<Int, Int> {
        val body = ByteWriter().u32(size.toLong()).bytes(sha256)
            .bytes(if (signature.size == 64) signature else ByteArray(64)) // all zeros means unsigned
            .toByteArray()
        try {
            return Wire.firmwareBegin(call(Op.FW_BEGIN, body))
        } catch (e: DeviceError) {
            when (e.code) {
                "busy" -> throw DeviceError(
                    "update_blocked",
                    "The device can't take an update now. It must be idle, with no scheduled event in the next 15 minutes.",
                )
                "too_large" -> throw DeviceError("too_large", "That firmware is too large for this device.")
                else -> throw e
            }
        }
    }

    /** Finish the transfer. The device checks the image and restarts after the returned delay (milliseconds). */
    internal suspend fun firmwareEnd(): Int = Wire.firmwareEnd(call(Op.FW_END, timeoutMs = 20_000))

    internal suspend fun firmwareAbort() {
        runCatching { call(Op.FW_ABORT) }
    }

    // endregion

    suspend fun deviceTime(): Instant = Instant.ofEpochMilli(Wire.timeGet(call(Op.TIME_GET)))

    /**
     * Compare clocks (compensating for round-trip delay) and set the device
     * clock to this phone's if they differ by more than [threshold] seconds.
     * The phone is the time authority. A busy device is left alone.
     */
    suspend fun syncClock(threshold: Double = 0.25): ClockSync {
        val sent = Instant.now()
        val device = deviceTime()
        val received = Instant.now()
        val halfTrip = (received.toEpochMilli() - sent.toEpochMilli()) / 2.0 / 1000.0
        val offset = (device.toEpochMilli() - sent.toEpochMilli()) / 1000.0 - halfTrip

        if (Math.abs(offset) <= threshold) return ClockSync(offset, applied = false)

        return try {
            // the write takes about half a round trip to arrive
            val target = Instant.now().plusMillis((halfTrip * 1000).toLong())
            call(Op.TIME_SET, ByteWriter().u64(target.toEpochMilli()).toByteArray())
            ClockSync(offset, applied = true)
        } catch (e: DeviceError) {
            if (e.code == "busy") ClockSync(offset, applied = false) else throw e
        }
    }

    // endregion
}
