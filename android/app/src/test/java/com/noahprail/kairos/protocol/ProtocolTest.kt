package com.noahprail.kairos.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class FramingTest {
    @Test
    fun fragmentsMatchReferenceVectors() {
        for (v in Vectors.load().list("framing")) {
            val frags = Framing.fragments(v.hex("payloadHex"), v.int("maxFragment"))
            assertEquals(v.str("name"), v.arr("fragments").map { it.toString().trim('"') }, frags.map { it.hex() })
        }
    }

    @Test
    fun referenceVectorsReassemble() {
        for (v in Vectors.load().list("framing")) {
            val r = Reassembler()
            var message: ByteArray? = null
            for (hex in v.arr("fragments").map { it.toString().trim('"') }) {
                val result = r.push(hexBytes(hex))
                if (result is Reassembler.Result.Message) message = result.data
            }
            assertArrayEquals(v.str("name"), v.hex("payloadHex"), message)
        }
    }

    @Test
    fun roundTripAcrossSizesAndMtus() {
        for (maxFragment in listOf(20, 61, 182, 514)) {
            for (size in listOf(1, 17, 18, 19, 20, 21, 500, 2048)) {
                val payload = ByteArray(size) { (it % 251).toByte() }
                val r = Reassembler()
                var out: ByteArray? = null
                for (frag in Framing.fragments(payload, maxFragment)) {
                    assertTrue(frag.size <= maxFragment)
                    val result = r.push(frag)
                    if (result is Reassembler.Result.Message) out = result.data
                }
                assertArrayEquals("maxFragment $maxFragment size $size", payload, out)
            }
        }
    }

    @Test
    fun indexWrapsAt128() {
        val payload = ByteArray(2048) { 1 }
        val frags = Framing.fragments(payload, 10)
        assertTrue(frags.size > 128)
        assertEquals(0, frags[128][0].toInt())
        val r = Reassembler()
        var out: ByteArray? = null
        for (f in frags) (r.push(f) as? Reassembler.Result.Message)?.let { out = it.data }
        assertArrayEquals(payload, out)
    }

    @Test
    fun outOfOrderAndOrphanFragmentsAreErrors() {
        val frags = Framing.fragments(ByteArray(60) { 1 }, 20)
        val r = Reassembler()
        r.push(frags[0])
        assertEquals(Reassembler.Result.Error(FramingError.BAD_FRAME), r.push(frags[2]))
        assertFalse(r.isPending)

        assertEquals(Reassembler.Result.Error(FramingError.BAD_FRAME), Reassembler().push(frags[1]))
    }

    @Test
    fun oversizedMessagesAreRejectedAndDropped() {
        val r = Reassembler(maxMessage = 100)
        val frags = Framing.fragments(ByteArray(200) { 1 }, 20)
        assertEquals(Reassembler.Result.Error(FramingError.TOO_LARGE), r.push(frags[0]))
        for (f in frags.drop(1)) assertEquals(Reassembler.Result.NeedMore, r.push(f))
        val ok = Framing.fragments("hi".toByteArray(), 20)
        val result = r.push(ok[0])
        assertTrue(result is Reassembler.Result.Message)
        assertArrayEquals("hi".toByteArray(), (result as Reassembler.Result.Message).data)
    }
}

class AuthTest {
    @Test
    fun proofsMatchReferenceVectors() {
        val a = Vectors.load().auth
        val secret = a.hex("secretHex")
        val nonce = a.hex("nonceHex")
        val clientNonce = a.hex("clientNonceHex")

        assertEquals(a.str("clientProofHex"), AuthProof.client(secret, nonce, clientNonce).hex())
        assertEquals(a.str("serverProofHex"), AuthProof.server(secret, nonce, clientNonce).hex())
    }
}

class StatusTest {
    @Test
    fun statusDecodesReferenceVectors() {
        for (v in Vectors.load().list("status")) {
            val e = v.obj("expected")
            val s = StatusValue.parse(v.hex("hex"))
            assertNotNull(s)
            s!!
            assertEquals(e.int("flags"), s.flags)
            assertEquals(e.str("phase"), s.phase.name.lowercase())
            assertEquals(e.long("configRev"), s.configRev)
            assertEquals(e.long("nextEventAt"), s.nextEventAt)
            assertEquals(e.str("nextEventType"), s.nextEventType.name.lowercase())
            assertEquals(e.int("attemptsRemaining"), s.attemptsRemaining)
            assertEquals(e.optInt("nextAttempt"), s.nextAttempt)
        }
    }

    @Test
    fun flagsAndShortData() {
        val s = StatusValue.parse(Vectors.load().list("status")[1].hex("hex"))!!
        assertTrue(s.recording)
        assertTrue(s.pairingOpen)
        assertTrue(s.clockValid)
        assertNull(s.nextEventDate)
        assertNull(s.nextAttempt)
        assertNull(StatusValue.parse(byteArrayOf(1, 2, 3)))
        assertNull(StatusValue.parse(ByteArray(12))) // the v1 length
    }

    @Test
    fun deviceInfoDecodesReferenceVectors() {
        for (v in Vectors.load().list("deviceInfo")) {
            val e = v.obj("expected")
            val info = DeviceInfo.parse(v.hex("hex"))
            assertEquals(DeviceInfo(e.int("v"), e.str("id"), e.str("name"), e.str("cam"), e.str("fw")), info)
        }
        assertNull(DeviceInfo.parse(byteArrayOf(2, 1, 2)))
    }
}

class WireTest {
    @Test
    fun configVectorsEncodeAndDecode() {
        for (v in Vectors.load().list("config")) {
            val config = configFrom(v.obj("config"))
            assertEquals(v.str("name"), v.str("hex"), Wire.encodeConfig(config).hex())
            assertTrue(v.str("name"), Wire.decodeConfig(v.hex("hex")).sameAs(config))
        }
    }

    @Test
    fun malformedConfigsAreRefused() {
        val ok = Vectors.load().list("config")[0].hex("hex")
        assertThrows(DeviceError::class.java) { Wire.decodeConfig(ok.also { it[0] = 9 }.copyOf()) }
        val fresh = Vectors.load().list("config")[0].hex("hex")
        assertThrows(DeviceError::class.java) { Wire.decodeConfig(fresh.copyOf(fresh.size - 1)) }
        val wrongType = Vectors.load().list("config")[0].hex("hex").also { it[1] = 7 }
        assertThrows(DeviceError::class.java) { Wire.decodeConfig(wrongType) }
    }

    @Test
    fun configEncodingChecksLimitsBeforeSending() {
        val base = CameraConfig()
        assertEquals("name", assertThrows(DeviceError::class.java) { Wire.encodeConfig(base.copy(name = "x".repeat(33))) }.field)
        assertEquals("zcam.cameraIp", assertThrows(DeviceError::class.java) { Wire.encodeConfig(base.copy(zcam = CameraConfig.Zcam("192.168.1"))) }.field)
        assertThrows(DeviceError::class.java) { Wire.encodeConfig(base.copy(zcam = CameraConfig.Zcam("300.1.1.1"))) }
        Wire.encodeConfig(base.copy(zcam = CameraConfig.Zcam("")))
        val many = List(31) { CameraConfig.Attempt("a$it", it.toLong()) }
        assertEquals("attempts", assertThrows(DeviceError::class.java) { Wire.encodeConfig(base.copy(attempts = many)) }.field)
    }

    @Test
    fun namesAreCountedInBytes() {
        val c = CameraConfig(name = "\uD83D\uDE80".repeat(9)) // 9 characters, 36 bytes
        assertThrows(DeviceError::class.java) { Wire.encodeConfig(c) }
    }

    private fun requestBody(op: Op, f: kotlinx.serialization.json.JsonObject): ByteArray = when (op) {
        Op.AUTH -> ByteWriter().bytes(f.hex("clientNonceHex")).bytes(f.hex("proofHex")).toByteArray()
        Op.AUTH_PAIR -> f.hex("secretHex")
        Op.TIME_SET -> ByteWriter().u64(f.long("nowMs")).toByteArray()
        Op.CONFIG_SET -> Wire.encodeConfig(configFrom(f.obj("config")))
        Op.LOGS_GET -> ByteWriter().u32(f.long("after")).u8(f.int("limit")).toByteArray()
        Op.LOGS_SUBSCRIBE -> ByteWriter().u8(if (f.bool("enabled")) 1 else 0).u32(f.long("after")).toByteArray()
        else -> ByteArray(0)
    }

    private val ops = mapOf(
        "hello" to Op.HELLO, "auth" to Op.AUTH, "auth.pair" to Op.AUTH_PAIR, "time.get" to Op.TIME_GET,
        "time.set" to Op.TIME_SET, "config.get" to Op.CONFIG_GET, "config.set" to Op.CONFIG_SET, "jobs.get" to Op.JOBS_GET,
        "camera.wake" to Op.CAMERA_WAKE, "camera.start" to Op.CAMERA_START, "camera.stop" to Op.CAMERA_STOP,
        "camera.sleep" to Op.CAMERA_SLEEP, "test.run" to Op.TEST_RUN, "logs.get" to Op.LOGS_GET, "logs.subscribe" to Op.LOGS_SUBSCRIBE,
    )

    @Test
    fun requestVectorsEncode() {
        for (v in Vectors.load().list("messages").filter { it.str("kind") == "request" }) {
            val op = ops.getValue(v.str("op"))
            val message = Wire.request(op, v.int("id"), requestBody(op, v.obj("fields")))
            assertEquals(v.str("name"), v.str("hex"), message.hex())
        }
    }

    @Test
    fun responseVectorsDecode() {
        for (v in Vectors.load().list("messages").filter { it.str("kind") == "response" }) {
            val f = v.obj("fields")
            val m = Wire.parse(v.hex("hex")) as Wire.Message.Response
            assertEquals(v.int("id"), m.id)
            assertEquals(0, m.status)
            when (v.str("op")) {
                "hello" -> Wire.hello(m.body).let {
                    assertEquals(f.int("v"), it.v)
                    assertEquals(f.bool("paired"), it.paired)
                    assertArrayEquals(f.hex("nonceHex"), it.nonce)
                    assertEquals(f.long("nowMs"), it.nowMs)
                    assertEquals(f.int("maxMessage"), it.maxMessage)
                }
                "time.get" -> assertEquals(f.long("nowMs"), Wire.timeGet(m.body))
                "time.set" -> ByteReader(m.body).let {
                    assertEquals(f.long("nowMs"), it.u64())
                    assertEquals(f.int("offsetMs"), it.i32())
                }
                "config.get" -> assertTrue(Wire.decodeConfig(m.body).sameAs(configFrom(f.obj("config"))))
                "config.set" -> assertEquals(f.long("configRev"), ByteReader(m.body).u32())
                "jobs.get" -> assertEquals(f.arr("states").map { it.toString().trim('"') }, Wire.jobStates(m.body))
                "camera.start", "camera.stop" -> assertEquals(f.bool("recording"), ByteReader(m.body).u8() != 0)
                "logs.get" -> Wire.logs(m.body).let { page ->
                    assertEquals(f.int("last"), page.last)
                    assertEquals(f.bool("more"), page.more)
                    assertEquals(f.arr("lines").map { (it as kotlinx.serialization.json.JsonObject).int("seq") }, page.entries.map { it.seq })
                    assertEquals(f.arr("lines").map { (it as kotlinx.serialization.json.JsonObject).str("msg") }, page.entries.map { it.msg })
                }
                "auth" -> assertEquals(f.str("proofHex"), m.body.hex())
                else -> assertEquals(v.str("name"), 0, m.body.size)
            }
        }
    }

    @Test
    fun eventVectorsDecode() {
        for (v in Vectors.load().list("messages").filter { it.str("kind") == "event" }) {
            val f = v.obj("fields")
            val m = Wire.parse(v.hex("hex")) as Wire.Message.Event
            val event = Wire.decodeEvent(m.code, m.body)
            assertNotNull(v.str("name"), event)
            when (v.str("ev")) {
                "job" -> (event as DeviceEvent.Job).event.let {
                    assertEquals(f.int("attempt"), it.attempt)
                    assertEquals(f.str("step"), it.step)
                    assertEquals(f.long("at"), it.at.epochSecond)
                }
                "config" -> assertEquals(f.int("configRev"), (event as DeviceEvent.Config).revision)
                "test" -> (event as DeviceEvent.Test).event.let {
                    assertEquals(f.str("step"), it.step)
                    assertEquals(f.bool("ok"), it.ok)
                    assertEquals(f.str("error"), it.error ?: "")
                }
                "log" -> (event as DeviceEvent.Log).entry.let {
                    assertEquals(f.int("seq"), it.seq)
                    assertEquals(f.str("ns"), it.ns)
                    assertEquals(f.str("msg"), it.msg)
                }
                else -> fail(v.str("name"))
            }
        }
    }

    @Test
    fun errorVectorsDecode() {
        for (v in Vectors.load().list("errors")) {
            val d = v.obj("detail")
            val m = Wire.parse(v.hex("hex")) as Wire.Message.Response
            assertEquals(v.int("id"), m.id)
            val error = Wire.deviceError(m.status, m.body)
            assertEquals(v.str("name"), v.str("code"), error.code)
            assertEquals(v.str("name"), d.optInt("retryAfter"), error.retryAfter)
            if (v.str("code") == "invalid_config") {
                assertEquals(Wire.fieldName(d.int("field"), d.int("index")), error.field)
                assertEquals(Wire.reasonText(d.int("reason")), error.rawMessage)
            }
            if (v.str("code") == "internal") d.optStr("message")?.let { assertEquals(it, error.rawMessage) }
        }
    }

    @Test
    fun anUnknownEventIsIgnoredAndTrailingBytesAreTolerated() {
        assertNull(Wire.decodeEvent(0x7E, ByteArray(0)))
        assertNotNull(Wire.decodeEvent(0x02, byteArrayOf(9, 0, 0, 0, 77, 77)))
        assertFalse(ByteReader(byteArrayOf(1, 2)).also { it.u32() }.ok)
    }
}

class ModelTest {
    @Test
    fun attemptsCompareByNameAndTimeNotLocalId() {
        val a = CameraConfig.Attempt("A", 100)
        val b = CameraConfig.Attempt("A", 100)
        assertNotEquals(a.localId, b.localId)
        assertTrue(CameraConfig(attempts = listOf(a)).sameAs(CameraConfig(attempts = listOf(b))))
        assertEquals(100L, CameraConfig.Attempt.create("A", Instant.ofEpochMilli(100_900)).time)
    }

    @Test
    fun errorMessagesAreFriendly() {
        assertTrue(DeviceError("pairing_closed", "x").message!!.contains("button"))
        assertEquals("Too many failed attempts. Try again in 30 s.", DeviceError("locked_out", "x", retryAfter = 30).message)
        assertEquals("attempts[2].time: must be in the future", Wire.deviceError(6, byteArrayOf(12, 2, 4)).message)
        assertEquals("Too many failed attempts. Try again in 300 s.", Wire.deviceError(3, byteArrayOf(44, 1)).message)
    }
}
