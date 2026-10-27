package com.noahprail.kairos.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class FirmwareVectorTest {
    private fun firmware() = Vectors.load().root["firmware"] as JsonObject
    private fun JsonObject.list(key: String) = (this[key] as JsonArray).map { it as JsonObject }

    @Test
    fun imagesAreChecked() {
        for (v in firmware().list("images")) {
            val chip = Chip.fromId(v.str("chip"))!!
            val e = v.obj("expected")
            val result = FirmwareImage.check(v.hex("hex"), chip)
            when (e.str("status")) {
                "ok" -> assertEquals(v.str("name"), FirmwareCheck.Ok(e.optStr("version")), result)
                "not_image" -> assertEquals(v.str("name"), FirmwareCheck.NotImage, result)
                "wrong_chip" -> assertEquals(v.str("name"), FirmwareCheck.WrongChip, result)
                "not_app" -> assertEquals(v.str("name"), FirmwareCheck.NotApp, result)
                else -> fail(e.str("status"))
            }
        }
    }

    @Test
    fun versionsCompare() {
        for (v in firmware().list("versions")) {
            assertEquals("${v.str("a")} vs ${v.str("b")}", v.optInt("expected"), FirmwareVersion.compare(v.str("a"), v.str("b")))
        }
    }

    @Test
    fun manifestsAreAssessed() {
        for (v in firmware().list("manifests")) {
            val manifest = ReleaseManifest.parse(Json.encodeToString(JsonObject.serializer(), v.obj("manifest")).toByteArray())!!
            val chip = Chip.fromId(v.str("chip"))!!
            val result = manifest.assess(chip, v.str("deviceFw"), v.int("appProtocol"))
            assertEquals(v.str("name"), v.obj("expected").str("status"), result.status.id)
        }
    }

    @Test
    fun firmwareWritesMatchTheVectors() {
        for (v in Vectors.load().list("firmwareWrites")) {
            val bytes = ByteWriter().u32(v.long("offset")).bytes(v.hex("dataHex")).toByteArray()
            assertEquals(v.str("name"), v.str("hex"), bytes.hex())
        }
    }

    @Test
    fun signatureFilesAcceptRawBytesOrHex() {
        val raw = ByteArray(64) { it.toByte() }
        assertArrayEquals(raw, FirmwareImage.signature(raw))
        assertArrayEquals(raw, FirmwareImage.signature((raw.hex() + "\n").toByteArray()))
        assertNull(FirmwareImage.signature(ByteArray(10)))
        assertNull(FirmwareImage.signature("zz".repeat(64).toByteArray()))
    }
}

/** A fake device that behaves like the firmware's updater: in-order writes, a rewind request on a gap, lost writes. */
class FakeUpdatingDevice : DeviceTransport {
    override var maxWriteLength = 64
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val notifications: Flow<ByteArray> = channel.receiveAsFlow()
    private val reassembler = Reassembler()

    var window = 512
    var received = ByteArray(0)
    var expectedSize = 0
    var expectedSha = ByteArray(0)
    var beginSignature = ByteArray(0)
    var begun = false
    var ended = false
    var aborted = false
    var drop = emptySet<Int>()
    var writes = 0
    var failWith: Int? = null
    var beginError: Pair<Int, ByteArray>? = null
    var resumeAt = 0
    private var lastReported = 0

    override suspend fun write(data: ByteArray) {
        val message = (reassembler.push(data) as? Reassembler.Result.Message)?.data ?: return
        val id = message[1].toInt() and 0xFF
        val body = ByteReader(message.copyOfRange(2, message.size))
        when (message[0].toInt() and 0xFF) {
            0x30 -> {
                beginError?.let { (status, extra) -> reply(byteArrayOf(0x80.toByte(), id.toByte(), status.toByte()) + extra); return }
                expectedSize = body.u32().toInt()
                expectedSha = body.bytes(32)
                beginSignature = body.bytes(64)
                begun = true
                received = ByteArray(resumeAt)
                lastReported = resumeAt
                reply(ByteWriter().u8(0x80).u8(id).u8(0).u32(resumeAt.toLong()).u16(window).toByteArray())
            }
            0x31 -> {
                ended = true
                reply(ByteWriter().u8(0x80).u8(id).u8(0).u16(500).toByteArray())
            }
            0x32 -> {
                aborted = true
                begun = false
                reply(byteArrayOf(0x80.toByte(), id.toByte(), 0))
            }
            else -> reply(byteArrayOf(0x80.toByte(), id.toByte(), 4))
        }
    }

    override suspend fun writeFirmware(data: ByteArray) {
        val index = writes++
        if (index in drop) return
        val r = ByteReader(data)
        val offset = r.u32().toInt()
        val bytes = data.copyOfRange(4, data.size)
        if (!begun || ended) return
        failWith?.let {
            event(3, received.size, it)
            begun = false
            return
        }
        if (offset > received.size) {
            event(0, received.size, 0) // a gap
            return
        }
        val skip = received.size - offset
        if (skip >= bytes.size) return
        received += bytes.copyOfRange(skip, bytes.size)
        if (received.size - lastReported >= window / 4 || received.size == expectedSize) {
            lastReported = received.size
            event(0, received.size, 0)
        }
    }

    private fun reply(message: ByteArray) {
        for (frag in Framing.fragments(message, 20)) channel.trySend(frag)
    }

    private fun event(state: Int, offset: Int, reason: Int) {
        reply(ByteWriter().u8(0x81).u8(0x05).u8(state).u32(offset.toLong()).u8(reason).toByteArray())
    }

    fun disconnect() {
        channel.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareUploaderTest {
    private fun image(size: Int) = ByteArray(size) { ((it * 31 + 7) and 0xFF).toByte() }

    private fun TestScope.uploader(device: FakeUpdatingDevice, stallMs: Long = 150) =
        FirmwareUploader(DeviceClient(device, backgroundScope), device, stallTimeoutMs = stallMs, maxStalls = 4)

    @Test
    fun aWholeImageIsSentInOrderWithinTheWindow() = runTest {
        val device = FakeUpdatingDevice()
        val img = image(5000)
        val seen = mutableListOf<Double>()
        val delay = uploader(device).upload(img, ByteArray(64) { 7 }) { seen += it.fraction }
        assertEquals(500, delay)
        assertArrayEquals(img, device.received)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(img), device.expectedSha)
        assertArrayEquals(ByteArray(64) { 7 }, device.beginSignature)
        assertTrue(device.ended)
        assertEquals(1.0, seen.last(), 0.0)
        assertEquals(seen.sorted(), seen)
    }

    @Test
    fun anUnsignedImageSendsAllZeros() = runTest {
        val device = FakeUpdatingDevice()
        uploader(device).upload(image(2000), ByteArray(0)) { }
        assertArrayEquals(ByteArray(64), device.beginSignature)
    }

    @Test
    fun lostWritesAreSentAgainFromTheDevicesOffset() = runTest {
        val device = FakeUpdatingDevice()
        device.drop = setOf(3, 4, 9)
        val img = image(4000)
        uploader(device).upload(img, ByteArray(64)) { }
        assertArrayEquals(img, device.received)
        assertTrue(device.writes > 4000 / 60)
    }

    @Test
    fun aLostFinalWriteIsRecoveredByTheStallTimeout() = runTest {
        val device = FakeUpdatingDevice()
        val img = image(1000)
        val payload = device.maxWriteLength - 4
        device.drop = setOf((img.size + payload - 1) / payload - 1) // the last chunk
        uploader(device).upload(img, ByteArray(64)) { }
        assertArrayEquals(img, device.received)
    }

    @Test
    fun aResumedTransferStartsAtTheDevicesOffset() = runTest {
        val device = FakeUpdatingDevice()
        device.resumeAt = 1200
        val img = image(3000)
        var first: Double? = null
        uploader(device).upload(img, ByteArray(64)) { if (first == null) first = it.fraction }
        assertEquals(0.4, first!!, 1e-9)
        assertArrayEquals(img.copyOfRange(1200, 3000), device.received.copyOfRange(1200, 3000))
        assertEquals((1800 + (device.maxWriteLength - 4) - 1) / (device.maxWriteLength - 4), device.writes)
    }

    @Test
    fun theDeviceRefusingTheImageSurfacesItsReason() = runTest {
        val device = FakeUpdatingDevice()
        device.failWith = 3 // chip
        try {
            uploader(device).upload(image(3000), ByteArray(64)) { }
            fail("should fail")
        } catch (e: DeviceError) {
            assertEquals("bad_image", e.code)
            assertEquals(Wire.badImageText(3), e.rawMessage)
        }
        assertFalse(device.aborted) // the device already dropped it
    }

    @Test
    fun beginErrorsAreExplained() = runTest {
        val cases = listOf(
            Triple(5, byteArrayOf(), "update_blocked" to "15 minutes"),
            Triple(7, byteArrayOf(), "too_large" to "too large"),
            Triple(10, byteArrayOf(6), "bad_image" to "isn't signed"),
            Triple(10, byteArrayOf(7), "bad_image" to "signature"),
            Triple(4, byteArrayOf(), "not_supported" to "does not support"),
        )
        for ((status, body, expected) in cases) {
            val device = FakeUpdatingDevice()
            device.beginError = status to body
            try {
                uploader(device).upload(image(2000), ByteArray(64)) { }
                fail("should fail")
            } catch (e: DeviceError) {
                assertEquals(expected.first, e.code)
                assertTrue(e.rawMessage, e.rawMessage.contains(expected.second))
            }
        }
    }

    @Test
    fun cancellingAbortsTheTransfer() = runTest {
        val device = FakeUpdatingDevice()
        device.drop = (0 until 10_000).toSet() // nothing ever arrives, so the upload keeps waiting
        val up = uploader(device, stallMs = 5000)
        val failed = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                up.upload(image(3000), ByteArray(64)) { }
                failed.complete(null)
            } catch (e: Throwable) {
                failed.complete(e)
            }
        }
        testScheduler.runCurrent() // not advanceUntilIdle: that would run the stall timeouts out
        job.cancel()
        job.join()
        advanceUntilIdle()
        assertTrue(device.aborted)
    }

    @Test
    fun anUnansweredTransferTimesOutAndKeepsThePartialImage() = runTest {
        val device = FakeUpdatingDevice()
        device.drop = (0 until 10_000).toSet()
        try {
            uploader(device, stallMs = 50).upload(image(3000), ByteArray(64)) { }
            fail("should time out")
        } catch (e: DeviceError) {
            assertEquals("timeout", e.code)
        }
        assertFalse(device.aborted) // the device keeps it for two minutes, so the same image can resume
    }
}

// region GitHub releases

private class ScriptedFetcher(val responses: Map<String, HttpResult>) : HttpFetching {
    val urls = mutableListOf<String>()
    val etags = mutableListOf<String?>()

    override suspend fun fetch(url: String, etag: String?): HttpResult {
        urls += url
        etags += etag
        val r = responses[url] ?: throw java.io.IOException("offline")
        if (etag != null && etag == r.etag) return HttpResult(304, ByteArray(0), r.etag)
        return r
    }
}

class UpdateServiceTest {
    private val sha = "a".repeat(64)
    private val sig = "b".repeat(128)
    private val latestUrl = "https://github.com/nprail/kairos/releases/latest/download/manifest.json"

    private fun manifest(version: String): ByteArray {
        val entry = JsonObject(
            mapOf(
                "file" to JsonPrimitive("kairos-esp32s3-$version-app.bin"), "size" to JsonPrimitive(10),
                "sha256" to JsonPrimitive(sha), "sig" to JsonPrimitive(sig),
            ),
        )
        val json = JsonObject(
            mapOf(
                "version" to JsonPrimitive(version), "protocol" to JsonPrimitive(2), "minFrom" to JsonPrimitive("1.0.0"),
                "images" to JsonObject(mapOf("esp32s3" to entry)),
            ),
        )
        return json.toString().toByteArray()
    }

    @Test
    fun theLatestCheckIsThrottledToTenMinutesAndForceAsksAgain() = runTest {
        val fetcher = ScriptedFetcher(mapOf(latestUrl to HttpResult(200, manifest("1.3.0"), "\"v1\"")))
        var clock = 1_000_000L
        val service = UpdateService(fetcher, MemoryUpdateStore(), now = { clock })

        var result = service.latest()
        assertFalse(result.fromCache)
        assertEquals("1.3.0", result.manifest.version)
        assertEquals(1, fetcher.urls.size)

        clock += 599_000
        result = service.latest()
        assertTrue(result.fromCache)
        assertEquals("no request inside the window", 1, fetcher.urls.size)

        result = service.latest(force = true) // the Check button ignores the window
        assertTrue("a 304 keeps the saved copy", result.fromCache)
        assertEquals(2, fetcher.urls.size)
        assertEquals("\"v1\"", fetcher.etags.last())

        clock += 600_000
        service.latest()
        assertEquals(3, fetcher.urls.size)
    }

    @Test
    fun aFailedCheckDoesNotStartTheWindow() = runTest {
        val store = MemoryUpdateStore()
        try {
            UpdateService(ScriptedFetcher(emptyMap()), store).latest()
            fail("should fail")
        } catch (e: DeviceError) {
            assertEquals("network", e.code)
        }
        assertNull(store.lastCheck)
        val online = UpdateService(ScriptedFetcher(mapOf(latestUrl to HttpResult(200, manifest("1.3.0")))), store)
        assertFalse(online.latest().fromCache)
    }

    @Test
    fun anOfflinePhoneStillSeesTheSavedManifestInsideTheWindow() = runTest {
        val store = MemoryUpdateStore().also {
            it.manifest = manifest("1.3.0")
            it.lastCheck = System.currentTimeMillis()
        }
        assertTrue(UpdateService(ScriptedFetcher(emptyMap()), store).latest().fromCache)
    }

    @Test
    fun anUnusableAnswerIsAnError() = runTest {
        val service = UpdateService(ScriptedFetcher(mapOf(latestUrl to HttpResult(404, ByteArray(0)))), MemoryUpdateStore())
        try {
            service.latest()
            fail("should fail")
        } catch (e: DeviceError) {
            assertEquals("network", e.code)
        }
    }

    @Test
    fun releasesAreListedAndPrereleasesAreHiddenByDefault() = runTest {
        val list = """
        [{"tag_name":"v1.4.0-beta.1","prerelease":true,"draft":false,"assets":[{"name":"manifest.json","browser_download_url":"https://example.test/m1.json"}]},
         {"tag_name":"v1.3.0","prerelease":false,"draft":false,"assets":[{"name":"manifest.json","browser_download_url":"https://example.test/m2.json"},{"name":"x.bin","browser_download_url":"https://example.test/x"}]},
         {"tag_name":"v1.2.0","prerelease":false,"draft":true,"assets":[]}]
        """.trimIndent()
        val url = "https://api.github.com/repos/nprail/kairos/releases?per_page=30"
        val service = UpdateService(ScriptedFetcher(mapOf(url to HttpResult(200, list.toByteArray()))), MemoryUpdateStore())
        val stable = service.releases(includePrereleases = false)
        assertEquals(listOf("v1.3.0"), stable.map { it.tag })
        assertEquals("1.3.0", stable[0].version)
        assertEquals("https://example.test/m2.json", stable[0].manifestUrl)
        assertEquals(listOf("v1.4.0-beta.1", "v1.3.0"), service.releases(includePrereleases = true).map { it.tag })
    }

    @Test
    fun withPrereleasesTheLatestCheckFindsTheNewestReleaseOfAnyKind() = runTest {
        val list = """
        [{"tag_name":"v1.4.0-beta.1","prerelease":true,"draft":false,"assets":[{"name":"manifest.json","browser_download_url":"https://example.test/beta.json"}]},
         {"tag_name":"v1.3.0","prerelease":false,"draft":false,"assets":[{"name":"manifest.json","browser_download_url":"https://example.test/stable.json"}]}]
        """.trimIndent()
        val fetcher = ScriptedFetcher(
            mapOf(
                "https://api.github.com/repos/nprail/kairos/releases?per_page=30" to HttpResult(200, list.toByteArray()),
                "https://example.test/beta.json" to HttpResult(200, manifest("1.4.0-beta.1")),
                latestUrl to HttpResult(200, manifest("1.3.0")),
            ),
        )
        val service = UpdateService(fetcher, MemoryUpdateStore())
        assertEquals("1.4.0-beta.1", service.latest(force = true, includePrereleases = true).manifest.version)
        assertEquals("1.3.0", service.latest(force = true).manifest.version)
    }

    @Test
    fun aDownloadIsCheckedAgainstTheManifest() = runTest {
        val bytes = ByteArray(500) { (it % 251).toByte() }
        val entry = ReleaseManifest.Image("kairos-esp32s3-1.3.0-app.bin", bytes.size, UpdateService.sha256Hex(bytes), sig)
        val url = "https://github.com/nprail/kairos/releases/download/v1.3.0/kairos-esp32s3-1.3.0-app.bin"
        assertArrayEquals(bytes, UpdateService(ScriptedFetcher(mapOf(url to HttpResult(200, bytes))), MemoryUpdateStore()).download(entry, "1.3.0"))

        val corrupt = bytes.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        try {
            UpdateService(ScriptedFetcher(mapOf(url to HttpResult(200, corrupt))), MemoryUpdateStore()).download(entry, "1.3.0")
            fail("should fail")
        } catch (e: DeviceError) {
            assertEquals("bad_download", e.code)
        }
    }

    @Test
    fun theCacheKeepsVerifiedImagesAndTheNewestTwo() {
        val dir = Files.createTempDirectory("kairos-cache").toFile()
        try {
            val cache = FirmwareCache(dir, keep = 2)
            val a = ByteArray(100) { 1 }
            val b = ByteArray(100) { 2 }
            val c = ByteArray(100) { 3 }
            var t = 1_000_000L
            for (d in listOf(a, b, c)) {
                cache.store(d, UpdateService.sha256Hex(d))
                File(dir, UpdateService.sha256Hex(d) + ".bin").setLastModified(t)
                t += 10_000
            }
            // store() trims by modified time, which the test just set; trim again with the final order
            cache.store(c, UpdateService.sha256Hex(c))
            assertNull("the oldest was trimmed", cache.image(UpdateService.sha256Hex(a), 100))
            assertArrayEquals(b, cache.image(UpdateService.sha256Hex(b), 100))
            assertArrayEquals(c, cache.image(UpdateService.sha256Hex(c), 100))
            assertNull("a wrong size is not trusted", cache.image(UpdateService.sha256Hex(c), 99))
        } finally {
            dir.deleteRecursively()
        }
    }
}

// endregion
