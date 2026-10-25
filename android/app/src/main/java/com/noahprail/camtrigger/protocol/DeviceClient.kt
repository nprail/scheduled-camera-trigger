package com.noahprail.camtrigger.protocol

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
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

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
}

enum class CameraAction(val op: String) {
    WAKE("camera.wake"),
    START("camera.start"),
    STOP("camera.stop"),
    SLEEP("camera.sleep"),
}

private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
private fun unb64(text: String): ByteArray? = try {
    java.util.Base64.getDecoder().decode(text)
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Speaks the request/response protocol (docs/BLE_PROTOCOL.md §4) over a
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
    private val pending = mutableMapOf<Int, CompletableDeferred<JsonObject>>()
    private var inFlight: Int? = null
    private val mutex = Mutex()
    private val readJob: kotlinx.coroutines.Job

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
        val obj = try {
            json.parseToJsonElement(data.decodeToString()).jsonObject
        } catch (_: Exception) {
            return
        }

        val ev = obj["ev"]?.jsonPrimitive?.contentOrNull
        if (ev != null) {
            decodeEvent(ev, obj)?.let { eventFlow.tryEmit(it) }
            return
        }

        val id = obj["id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
        if (id != null) {
            resolve(id, Result.success(obj))
        } else {
            // error response to a frame the device couldn't attribute (id: null)
            inFlight?.let { resolve(it, Result.success(obj)) }
        }
    }

    private fun decodeEvent(name: String, obj: JsonObject): DeviceEvent? {
        val d = obj["d"] ?: return null
        return try {
            when (name) {
                "job" -> DeviceEvent.Job(json.decodeFromJsonElement(JobEvent.serializer(), d))
                "test" -> DeviceEvent.Test(json.decodeFromJsonElement(TestEvent.serializer(), d))
                "log" -> DeviceEvent.Log(json.decodeFromJsonElement(LogEntry.serializer(), d))
                "config" -> DeviceEvent.Config(json.decodeFromJsonElement(ConfigEvent.serializer(), d).configRev)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolve(id: Int, result: Result<JsonObject>) {
        val deferred = pending.remove(id) ?: return
        if (inFlight == id) inFlight = null
        result.onSuccess { deferred.complete(it) }.onFailure { deferred.completeExceptionally(it) }
    }

    private fun linkClosed() {
        for (id in pending.keys.toList()) resolve(id, Result.failure(DeviceError.disconnected))
    }

    // endregion

    // region Sending

    /** Send one request and decode its `d` payload. */
    internal suspend fun <R> call(
        op: String,
        params: JsonElement,
        result: DeserializationStrategy<R>,
        timeoutMs: Long = 10_000,
    ): R = mutex.withLock {
        val id = nextId++

        val body = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("id" to json.encodeToJsonElement(Int.serializer(), id), "op" to json.encodeToJsonElement(String.serializer(), op), "d" to params)),
        ).toByteArray(Charsets.UTF_8)
        if (body.size > minOf(maxMessage, Framing.MAX_MESSAGE)) {
            throw DeviceError("too_large", "Request is too large for the device.")
        }
        val frames = Framing.fragments(body, maxOf(transport.maxWriteLength, 20))

        val deferred = CompletableDeferred<JsonObject>()
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

        if (response["ok"]?.jsonPrimitive?.boolean != true) {
            val err = response["err"]
            throw err?.let { json.decodeFromJsonElement(ErrorBody.serializer(), it).asError() }
                ?: DeviceError("internal", "Malformed response.")
        }
        json.decodeFromJsonElement(result, response["d"] ?: JsonObject(emptyMap()))
    }

    private suspend fun <R> call(op: String, result: DeserializationStrategy<R>, timeoutMs: Long = 10_000): R =
        call(op, JsonObject(emptyMap()), result, timeoutMs)

    private fun <T> encode(serializer: kotlinx.serialization.SerializationStrategy<T>, value: T): JsonElement =
        json.encodeToJsonElement(serializer, value)

    // endregion

    // region Session

    suspend fun hello(): HelloResponse {
        val response = call("hello", HelloResponse.serializer())
        if (response.v != 1) {
            throw DeviceError("unsupported", "This device speaks protocol v${response.v}; this app supports v1.")
        }
        maxMessage = response.maxMessage
        return response
    }

    /** Prove knowledge of [secret] and verify the device does too. */
    suspend fun authenticate(secret: ByteArray, hello: HelloResponse) {
        val nonce = unb64(hello.nonce) ?: throw DeviceError("bad_request", "Bad nonce from device.")
        val clientNonce = AuthProof.randomBytes(16)
        val proof = AuthProof.client(secret, nonce, clientNonce)

        val reply = call(
            "auth",
            JsonObject(
                mapOf(
                    "clientNonce" to json.encodeToJsonElement(String.serializer(), b64(clientNonce)),
                    "proof" to json.encodeToJsonElement(String.serializer(), b64(proof)),
                ),
            ),
            AuthResponse.serializer(),
        )

        val expected = AuthProof.server(secret, nonce, clientNonce)
        if (!expected.contentEquals(unb64(reply.proof) ?: ByteArray(0))) {
            throw DeviceError("unauthorized", "The device could not prove it holds the pairing key.")
        }
    }

    /** Store a new shared secret on the device. Only works during the pairing window. */
    suspend fun pair(secret: ByteArray) {
        call(
            "auth.pair",
            JsonObject(mapOf("secret" to json.encodeToJsonElement(String.serializer(), b64(secret)))),
            Empty.serializer(),
        )
    }

    // endregion

    // region Operations

    suspend fun status(): StatusInfo = call("status.get", StatusInfo.serializer())

    suspend fun config(): CameraConfig = call("config.get", ConfigEnvelope.serializer()).config

    /** Replace the device config. Returns the new `configRev`. */
    suspend fun setConfig(config: CameraConfig): Int =
        call("config.set", encode(ConfigEnvelope.serializer(), ConfigEnvelope(config)), ConfigSetResponse.serializer()).configRev

    suspend fun jobs(): List<com.noahprail.camtrigger.protocol.Job> {
        val all = mutableListOf<com.noahprail.camtrigger.protocol.Job>()
        while (true) {
            val page = call(
                "jobs.get",
                JsonObject(
                    mapOf(
                        "offset" to json.encodeToJsonElement(Int.serializer(), all.size),
                        "limit" to json.encodeToJsonElement(Int.serializer(), 10),
                    ),
                ),
                JobsPage.serializer(),
            )
            all += page.jobs
            if (!page.more || page.jobs.isEmpty()) break
        }
        return all
    }

    suspend fun camera(action: CameraAction) {
        call(action.op, CameraResponse.serializer(), timeoutMs = 20_000)
    }

    suspend fun runTest() {
        call("test.run", StartedResponse.serializer())
    }

    data class LogPage(val entries: List<LogEntry>, val last: Int, val more: Boolean)

    suspend fun logs(after: Int = 0, limit: Int = 20): LogPage {
        val page = call(
            "logs.get",
            JsonObject(
                mapOf(
                    "after" to json.encodeToJsonElement(Int.serializer(), after),
                    "limit" to json.encodeToJsonElement(Int.serializer(), limit),
                ),
            ),
            LogsPage.serializer(),
        )
        return LogPage(page.logs, page.last, page.more)
    }

    /** Start (or stop) streaming log lines as log events, replaying anything after [after]. */
    suspend fun subscribeLogs(enabled: Boolean, after: Int? = null) {
        val params = buildMap<String, JsonElement> {
            put("enabled", json.encodeToJsonElement(Boolean.serializer(), enabled))
            if (after != null) put("after", json.encodeToJsonElement(Int.serializer(), after))
        }
        call("logs.subscribe", JsonObject(params), Empty.serializer())
    }

    suspend fun setWifi(enabled: Boolean) {
        call(
            "wifi.set",
            JsonObject(mapOf("enabled" to json.encodeToJsonElement(Boolean.serializer(), enabled))),
            EnabledResponse.serializer(),
        )
    }

    suspend fun deviceTime(): Instant {
        val reply = call("time.get", TimeResponse.serializer())
        return DeviceDate.parse(reply.now) ?: Instant.now()
    }

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
            call(
                "time.set",
                JsonObject(mapOf("now" to json.encodeToJsonElement(String.serializer(), DeviceDate.string(target)))),
                TimeResponse.serializer(),
            )
            ClockSync(offset, applied = true)
        } catch (e: DeviceError) {
            if (e.code == "busy") ClockSync(offset, applied = false) else throw e
        }
    }

    // endregion

    companion object {
        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
