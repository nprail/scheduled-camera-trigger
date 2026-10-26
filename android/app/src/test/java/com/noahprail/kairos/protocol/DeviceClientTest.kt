package com.noahprail.kairos.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * A scripted device: reassembles what the client writes, hands each request
 * to [respond], and notifies the reply (plus any extra messages) back.
 */
class FakeDevice : DeviceTransport {
    override var maxWriteLength = 20
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val notifications: Flow<ByteArray> = channel.receiveAsFlow()
    private val reassembler = Reassembler()
    val requests = mutableListOf<JsonObject>()
    var respond: (JsonObject) -> List<JsonObject> = { emptyList() }

    override suspend fun write(data: ByteArray) {
        val result = reassembler.push(data) as? Reassembler.Result.Message ?: return
        val request = Json.parseToJsonElement(result.data.decodeToString()).jsonObject
        requests += request
        for (reply in respond(request)) send(reply)
    }

    fun send(obj: JsonObject) {
        val body = obj.toString().toByteArray()
        for (frag in Framing.fragments(body, 20)) channel.trySend(frag)
    }

    fun disconnect() {
        channel.close()
    }
}

private fun ok(request: JsonObject, d: JsonObject = JsonObject(emptyMap())) =
    buildJsonObject {
        put("id", request["id"]!!)
        put("ok", true)
        put("d", d)
    }

private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> throw IllegalArgumentException()
        }
    },
)

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceClientTest {
    private fun TestScope.client(device: FakeDevice) = DeviceClient(device, backgroundScope)

    private fun hello(req: JsonObject, nonce: String, paired: Boolean = true) = ok(
        req,
        obj(
            "v" to 1, "nonce" to nonce, "authRequired" to true, "paired" to paired,
            "now" to "2026-11-01T12:00:00.000Z", "maxMessage" to 4096,
        ),
    )

    @Test
    fun helloAndMutualAuthentication() = runTest {
        val vectors = Vectors.load().auth
        val secret = hexBytes(vectors.secretHex)
        val nonce = Base64.getDecoder().decode(vectors.nonceB64)
        val device = FakeDevice()

        device.respond = { req ->
            when (req["op"]!!.jsonPrimitive.content) {
                "hello" -> listOf(hello(req, vectors.nonceB64))
                "auth" -> {
                    val d = req["d"]!!.jsonObject
                    val clientNonce = Base64.getDecoder().decode(d["clientNonce"]!!.jsonPrimitive.content)
                    // the device checks the client's proof, then proves itself
                    assertEquals(
                        d["proof"]!!.jsonPrimitive.content,
                        Base64.getEncoder().encodeToString(AuthProof.client(secret, nonce, clientNonce)),
                    )
                    val proof = AuthProof.server(secret, nonce, clientNonce)
                    listOf(ok(req, obj("proof" to Base64.getEncoder().encodeToString(proof))))
                }
                else -> emptyList()
            }
        }

        val client = client(device)
        val hello = client.hello()
        assertTrue(hello.paired)
        client.authenticate(secret, hello)
    }

    @Test
    fun authRejectsADeviceThatCannotProveItself() = runTest {
        val device = FakeDevice()
        device.respond = { req ->
            when (req["op"]!!.jsonPrimitive.content) {
                "hello" -> listOf(hello(req, Base64.getEncoder().encodeToString(ByteArray(16))))
                else -> listOf(ok(req, obj("proof" to Base64.getEncoder().encodeToString(ByteArray(32)))))
            }
        }
        val client = client(device)
        val hello = client.hello()
        try {
            client.authenticate(ByteArray(16), hello)
            fail("expected failure")
        } catch (e: DeviceError) {
            assertEquals("unauthorized", e.code)
        }
    }

    @Test
    fun deviceErrorsAreSurfaced() = runTest {
        val device = FakeDevice()
        device.respond = { req ->
            listOf(
                obj(
                    "id" to req["id"]!!,
                    "ok" to false,
                    "err" to obj("code" to "invalid_config", "msg" to "is not a valid date", "field" to "attempts[0].time"),
                ),
            )
        }
        try {
            client(device).status()
            fail("expected failure")
        } catch (e: DeviceError) {
            assertEquals("invalid_config", e.code)
            assertEquals("attempts[0].time", e.field)
        }
    }

    @Test
    fun jobsAreFetchedAcrossPages() = runTest {
        val device = FakeDevice()
        fun job(i: Int) = obj(
            "id" to "j$i", "name" to "A$i", "camera" to "generic",
            "wakeUpTime" to "2026-11-02T17:38:35.000Z", "recordTime" to "2026-11-02T17:38:40.000Z",
            "launchTime" to "2026-11-02T17:39:00.000Z", "stopTime" to "2026-11-02T17:39:30.000Z",
            "state" to "pending",
        )
        device.respond = { req ->
            val offset = req["d"]!!.jsonObject["offset"]!!.jsonPrimitive.int
            val page = (offset until minOf(offset + 10, 25)).map(::job)
            listOf(ok(req, obj("jobs" to JsonArray(page), "total" to 25, "more" to (offset + page.size < 25))))
        }
        val jobs = client(device).jobs()
        assertEquals(25, jobs.size)
        assertEquals(3, device.requests.size)
    }

    @Test
    fun eventsAreDecoded() = runTest {
        val device = FakeDevice()
        val client = client(device)
        val events = mutableListOf<DeviceEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { client.events.collect { events += it } }
        testScheduler.runCurrent()

        device.send(obj("ev" to "job", "d" to obj("name" to "A", "step" to "wake", "at" to "2026-11-02T17:38:35.000Z")))
        device.send(obj("ev" to "test", "d" to obj("step" to "done", "ok" to true)))
        device.send(obj("ev" to "log", "d" to obj("seq" to 4, "ts" to "2026-11-02T17:38:35.000Z", "ns" to "INDEX", "msg" to "hi")))
        device.send(obj("ev" to "config", "d" to obj("configRev" to 9)))
        testScheduler.runCurrent()

        assertEquals(4, events.size)
        assertEquals(DeviceEvent.Job(JobEvent("A", "wake", "2026-11-02T17:38:35.000Z")), events[0])
        assertEquals(DeviceEvent.Test(TestEvent("done", true, null)), events[1])
        assertEquals("hi", (events[2] as DeviceEvent.Log).entry.msg)
        assertEquals(DeviceEvent.Config(9), events[3])
    }

    @Test
    fun clockSyncSetsTheDeviceClockOnlyWhenItDrifts() = runTest {
        val device = FakeDevice()
        var drift = 3.0
        device.respond = { req ->
            when (req["op"]!!.jsonPrimitive.content) {
                "time.get" -> listOf(
                    ok(req, obj("now" to DeviceDate.string(Instant.now().plusMillis((drift * 1000).toLong())))),
                )
                "time.set" -> {
                    drift = 0.0
                    listOf(ok(req, obj("now" to DeviceDate.string(Instant.now()), "offsetMs" to 3000)))
                }
                else -> emptyList()
            }
        }
        val client = client(device)

        val first = client.syncClock()
        assertTrue(first.applied)
        assertEquals(3.0, first.offset, 0.5)

        val second = client.syncClock()
        assertFalse(second.applied)
        assertEquals(1, device.requests.count { it["op"]!!.jsonPrimitive.content == "time.set" })
    }

    @Test
    fun requestsFailWhenTheLinkDrops() = runTest {
        val device = FakeDevice() // never answers
        val client = client(device)
        backgroundScope.launch { device.disconnect() }
        try {
            client.status()
            fail("expected failure")
        } catch (e: DeviceError) {
            assertEquals(DeviceError.disconnected, e)
        }
    }

    @Test
    fun requestsTimeOut() = runTest {
        val client = client(FakeDevice())
        try {
            client.status()
            fail("expected failure")
        } catch (e: DeviceError) {
            assertEquals(DeviceError.timeout, e)
        }
    }
}
