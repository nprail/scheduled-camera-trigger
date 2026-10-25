package com.noahprail.camtrigger.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

class FramingTest {
    @Test
    fun fragmentsMatchReferenceVectors() {
        for (v in Vectors.load().framing) {
            val frags = Framing.fragments(v.payload.toByteArray(), v.maxFragment)
            assertEquals(v.name, v.fragments, frags.map { it.hex() })
        }
    }

    @Test
    fun referenceVectorsReassemble() {
        for (v in Vectors.load().framing) {
            val r = Reassembler()
            var message: ByteArray? = null
            for (hex in v.fragments) {
                val result = r.push(hexBytes(hex))
                if (result is Reassembler.Result.Message) message = result.data
            }
            assertArrayEquals(v.name, v.payload.toByteArray(), message)
        }
    }

    @Test
    fun roundTripAcrossSizesAndMtus() {
        for (maxFragment in listOf(20, 61, 182, 514)) {
            for (size in listOf(1, 17, 18, 19, 20, 21, 500, 4096)) {
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
        val payload = ByteArray(4096) { 1 }
        val frags = Framing.fragments(payload, 20)
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
        val secret = hexBytes(a.secretHex)
        val nonce = Base64.getDecoder().decode(a.nonceB64)
        val clientNonce = Base64.getDecoder().decode(a.clientNonceB64)

        assertEquals(a.clientProofB64, Base64.getEncoder().encodeToString(AuthProof.client(secret, nonce, clientNonce)))
        assertEquals(a.serverProofB64, Base64.getEncoder().encodeToString(AuthProof.server(secret, nonce, clientNonce)))
    }
}

class StatusTest {
    @Test
    fun statusDecodesReferenceVectors() {
        for (v in Vectors.load().status) {
            val s = StatusValue.parse(hexBytes(v.hex))
            assertNotNull(s)
            s!!
            assertEquals(v.expected.flags, s.flags)
            assertEquals(v.expected.phase, s.phase.name.lowercase())
            assertEquals(v.expected.configRev, s.configRev)
            assertEquals(v.expected.nextEventAt, s.nextEventAt)
            assertEquals(v.expected.nextEventType, s.nextEventType.name.lowercase())
            assertEquals(v.expected.attemptsRemaining, s.attemptsRemaining)
        }
    }

    @Test
    fun flagsAndShortData() {
        val s = StatusValue.parse(hexBytes(Vectors.load().status[1].hex))!!
        assertTrue(s.recording)
        assertTrue(s.pairingOpen)
        assertFalse(s.wifi)
        assertNull(s.nextEventDate)
        assertNull(StatusValue.parse(byteArrayOf(1, 2, 3)))
    }
}

class ModelTest {
    @Test
    fun configRoundTripsWithoutLocalIds() {
        val text = """
        {"config":{"deviceId":"ABC123","configRev":3,"name":"Pad Cam","cameraType":"generic",
         "wakeUpTimeout":5,"startBefore":20,"endAfter":30,"logFile":"log.txt",
         "generic":{"releaseGpioPin":25},"zcam":{},
         "attempts":[{"name":"Attempt 1","time":"2026-11-02T17:39:00.000Z"}]}}
        """
        val json = DeviceClient.json
        val envelope = json.decodeFromString(ConfigEnvelope.serializer(), text)
        assertEquals(1, envelope.config.attempts.size)
        assertEquals(25, envelope.config.generic.releaseGpioPin)

        val out = json.encodeToString(CameraConfig.serializer(), envelope.config)
        assertFalse(out.contains("localId"))
        assertFalse(out.contains("null"))
        val again = json.decodeFromString(CameraConfig.serializer(), out)
        assertTrue(again.sameAs(envelope.config)) // attempts compare by name+time, not local id
    }

    @Test
    fun deviceDateParsesWithAndWithoutFraction() {
        assertEquals(DeviceDate.parse("2026-11-02T17:39:00.000Z"), DeviceDate.parse("2026-11-02T17:39:00Z"))
        assertEquals("2026-11-01T17:38:35.250Z", DeviceDate.string(Instant.ofEpochMilli(1_793_554_715_250)))
    }

    @Test
    fun errorMessagesAreFriendly() {
        assertTrue(DeviceError("pairing_closed", "x").message!!.contains("button"))
        assertEquals("logFile: is required", DeviceError("invalid_config", "is required", field = "logFile").message)
        assertEquals("Too many failed attempts. Try again in 30 s.", DeviceError("locked_out", "x", retryAfter = 30).message)
    }
}
