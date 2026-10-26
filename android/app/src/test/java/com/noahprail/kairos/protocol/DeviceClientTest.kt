package com.noahprail.kairos.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** One request the client wrote: its op code, id and body. */
class Sent(val op: Int, val id: Int, val body: ByteArray)

/**
 * A scripted device: reassembles what the client writes, hands each request to [respond], and
 * notifies the replies it returns.
 */
class FakeDevice : DeviceTransport {
    override var maxWriteLength = 20
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val notifications: Flow<ByteArray> = channel.receiveAsFlow()
    private val reassembler = Reassembler()
    val requests = mutableListOf<Sent>()
    var respond: (Sent) -> List<ByteArray> = { emptyList() }

    override suspend fun write(data: ByteArray) {
        val result = reassembler.push(data) as? Reassembler.Result.Message ?: return
        val b = result.data
        val sent = Sent(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b.copyOfRange(2, b.size))
        requests += sent
        for (reply in respond(sent)) send(reply)
    }

    fun send(message: ByteArray) {
        for (frag in Framing.fragments(message, 20)) channel.trySend(frag)
    }

    fun disconnect() {
        channel.close()
    }
}

private fun ok(id: Int, body: ByteArray = ByteArray(0)) = byteArrayOf(0x80.toByte(), id.toByte(), 0) + body
private fun fail(id: Int, status: Int, body: ByteArray = ByteArray(0)) = byteArrayOf(0x80.toByte(), id.toByte(), status.toByte()) + body

private fun helloBody(nonce: ByteArray, paired: Boolean = true, v: Int = 2) =
    ByteWriter().u8(v).u8(if (paired) 1 else 0).bytes(nonce).u64(1_793_554_715_482).u16(2048).toByteArray()

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceClientTest {
    private fun TestScope.client(device: FakeDevice) = DeviceClient(device, backgroundScope)

    @Test
    fun helloAndMutualAuthentication() = runTest {
        val a = Vectors.load().auth
        val secret = a.hex("secretHex")
        val nonce = a.hex("nonceHex")
        val device = FakeDevice()

        device.respond = { req ->
            when (req.op) {
                0x01 -> listOf(ok(req.id, helloBody(nonce)))
                0x02 -> {
                    val clientNonce = req.body.copyOf(16)
                    // the device checks the client's proof, then proves itself
                    assertEquals(AuthProof.client(secret, nonce, clientNonce).hex(), req.body.copyOfRange(16, 48).hex())
                    listOf(ok(req.id, AuthProof.server(secret, nonce, clientNonce)))
                }
                else -> emptyList()
            }
        }

        val client = client(device)
        val hello = client.hello()
        assertTrue(hello.paired)
        assertEquals(2048, hello.maxMessage)
        assertEquals(Instant.ofEpochMilli(1_793_554_715_482), hello.now)
        client.authenticate(secret, hello)
    }

    @Test
    fun authRejectsADeviceThatCannotProveItself() = runTest {
        val device = FakeDevice()
        device.respond = { req ->
            if (req.op == 0x01) listOf(ok(req.id, helloBody(ByteArray(16)))) else listOf(ok(req.id, ByteArray(32)))
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
    fun anUnsupportedVersionIsAnError() = runTest {
        val device = FakeDevice()
        device.respond = { req -> listOf(ok(req.id, helloBody(ByteArray(16), v = 1))) }
        try {
            client(device).hello()
            fail("expected failure")
        } catch (e: DeviceError) {
            assertEquals("unsupported", e.code)
            assertTrue(e.rawMessage.contains("Update the app"))
        }
    }

    @Test
    fun deviceErrorsAreSurfacedWithTheirDetail() = runTest {
        val device = FakeDevice()
        device.respond = { req -> listOf(fail(req.id, 6, byteArrayOf(12, 3, 4))) } // attempts[3].time is in the past
        try {
            client(device).setConfig(CameraConfig())
            fail("expected failure")
        } catch (e: DeviceError) {
            assertEquals("invalid_config", e.code)
            assertEquals("attempts[3].time", e.field)
            assertEquals("attempts[3].time: must be in the future", e.message)
        }
    }

    @Test
    fun anErrorWithIdZeroFailsTheRequestInFlight() = runTest {
        val device = FakeDevice()
        device.respond = { listOf(fail(0, 1)) } // a bad frame: the device could not read the id
        try {
            client(device).config()
            fail("expected failure")
        } catch (e: DeviceError) {
            assertEquals("bad_request", e.code)
        }
    }

    @Test
    fun jobsAreDerivedFromTheConfigAndTheStates() = runTest {
        val device = FakeDevice()
        device.respond = { req -> listOf(ok(req.id, byteArrayOf(3, 1, 0, 3))) } // active, pending, missed
        val config = CameraConfig(
            attempts = listOf(
                CameraConfig.Attempt("A", 1_793_554_740), CameraConfig.Attempt("B", 1_793_641_140), CameraConfig.Attempt("C", 1_793_700_000),
            ),
        )
        val jobs = client(device).jobs(config)
        assertEquals(listOf("A", "B", "C"), jobs.map { it.name })
        assertEquals(listOf("active", "pending", "missed"), jobs.map { it.state })
        assertEquals(Instant.ofEpochSecond(1_793_641_140), jobs[1].launchDate)
    }

    @Test
    fun aFullConfigRoundTripsAcrossManyFragments() = runTest {
        val device = FakeDevice()
        val full = configFrom(Vectors.load().list("config")[3].obj("config"))
        var received: ByteArray? = null
        device.respond = { req ->
            if (req.op == 0x08) {
                received = req.body
                listOf(ok(req.id, byteArrayOf(13, 0, 0, 0)))
            } else {
                listOf(ok(req.id, Wire.encodeConfig(full)))
            }
        }
        val client = client(device)
        assertEquals(13, client.setConfig(full))
        assertEquals(Wire.encodeConfig(full).hex(), received!!.hex())
        assertTrue(client.config().sameAs(full))
    }

    @Test
    fun eventsAreDecoded() = runTest {
        val device = FakeDevice()
        val client = client(device)
        val events = mutableListOf<DeviceEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { client.events.collect { events += it } }
        testScheduler.runCurrent()

        for (v in Vectors.load().list("messages").filter { it.str("kind") == "event" && it.str("name") in listOf("job wake", "test done", "log", "config") }) {
            device.send(v.hex("hex"))
        }
        testScheduler.runCurrent()

        assertEquals(4, events.size)
        assertEquals(DeviceEvent.Job(JobEvent(0, "wake", Instant.ofEpochSecond(1_793_554_715))), events[0])
        assertEquals(DeviceEvent.Config(14), events[1])
        assertEquals(DeviceEvent.Test(TestEvent("done", true, null)), events[2])
        assertEquals("Start 'Attempt 1'", (events[3] as DeviceEvent.Log).entry.msg)
    }

    @Test
    fun clockSyncSetsTheDeviceClockOnlyWhenItDrifts() = runTest {
        val device = FakeDevice()
        var drift = 3.0
        var setTo: Long? = null
        device.respond = { req ->
            if (req.op == 0x04) {
                listOf(ok(req.id, ByteWriter().u64(System.currentTimeMillis() + (drift * 1000).toLong()).toByteArray()))
            } else {
                setTo = ByteReader(req.body).u64()
                drift = 0.0
                listOf(ok(req.id, ByteWriter().u64(setTo!!).u32(3000).toByteArray()))
            }
        }
        val client = client(device)

        val first = client.syncClock()
        assertTrue(first.applied)
        assertEquals(3.0, first.offset, 0.5)
        assertNotNull(setTo)
        assertEquals(System.currentTimeMillis().toDouble(), setTo!!.toDouble(), 2000.0)

        val second = client.syncClock()
        assertFalse(second.applied)
        assertEquals(1, device.requests.count { it.op == 0x05 })
    }

    @Test
    fun aBusyDeviceKeepsItsClock() = runTest {
        val device = FakeDevice()
        device.respond = { req ->
            if (req.op == 0x04) listOf(ok(req.id, ByteWriter().u64(System.currentTimeMillis() + 9000).toByteArray())) else listOf(fail(req.id, 5))
        }
        assertFalse(client(device).syncClock().applied)
    }

    @Test
    fun logsAreRequestedAndParsed() = runTest {
        val device = FakeDevice()
        var asked: ByteArray? = null
        val line = Vectors.load().list("messages").first { it.str("name") == "logs.get" && it.str("kind") == "response" }
        device.respond = { req ->
            asked = req.body
            val m = Wire.parse(line.hex("hex")) as Wire.Message.Response
            listOf(ok(req.id, m.body))
        }
        val page = client(device).logs(after = 120, limit = 20)
        assertEquals("7800000014", asked!!.hex())
        assertEquals(2, page.entries.size)
        assertEquals(121, page.last)
        assertTrue(page.more)
    }

    @Test
    fun requestIdsStayInOneByte() = runTest {
        val device = FakeDevice()
        device.respond = { req -> listOf(ok(req.id, byteArrayOf(0))) }
        val client = client(device)
        repeat(300) { client.camera(CameraAction.WAKE) }
        assertTrue(device.requests.all { it.id >= 1 })
        assertEquals(if (300 % 255 == 0) 255 else 300 % 255, device.requests.last().id)
    }

    @Test
    fun requestsFailWhenTheLinkDrops() = runTest {
        val device = FakeDevice() // never answers
        val client = client(device)
        backgroundScope.launch { device.disconnect() }
        try {
            client.config()
            fail("expected failure")
        } catch (e: DeviceError) {
            assertEquals(DeviceError.disconnected, e)
        }
    }

    @Test
    fun requestsTimeOut() = runTest {
        val client = client(FakeDevice())
        try {
            client.config()
            fail("expected failure")
        } catch (e: DeviceError) {
            assertEquals(DeviceError.timeout, e)
        }
    }
}
