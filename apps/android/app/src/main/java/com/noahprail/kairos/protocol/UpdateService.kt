package com.noahprail.kairos.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// Finding, downloading and keeping firmware releases from GitHub (docs/ota-plan.md). Nothing here talks to
// a device, and nothing here runs unless the app asks it to.

class HttpResult(val status: Int, val data: ByteArray, val etag: String? = null)

fun interface HttpFetching {
    /** GET [url]. With an [etag], a "not modified" answer comes back as status 304 with no data. */
    suspend fun fetch(url: String, etag: String?): HttpResult
}

class UrlConnectionFetcher : HttpFetching {
    override suspend fun fetch(url: String, etag: String?): HttpResult = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Kairos")
            connection.setRequestProperty("Accept", "application/octet-stream, application/json")
            if (etag != null) connection.setRequestProperty("If-None-Match", etag)
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream.use { it.readBytes() } else ByteArray(0)
            HttpResult(status, body, connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    }
}

/** What the update check remembers between launches. */
interface UpdateStore {
    var lastCheck: Long?
    var manifest: ByteArray?
    var etag: String?

    /** The user's setting: check for updates when connecting. */
    var checkOnConnect: Boolean
    var includePrereleases: Boolean
}

class MemoryUpdateStore : UpdateStore {
    override var lastCheck: Long? = null
    override var manifest: ByteArray? = null
    override var etag: String? = null
    override var checkOnConnect = true
    override var includePrereleases = false
}

/** One published release, from the GitHub API. */
data class Release(val tag: String, val prerelease: Boolean, val manifestUrl: String?) {
    /** The version without the leading "v". */
    val version: String get() = tag.removePrefix("v")
}

class UpdateService(
    private val fetcher: HttpFetching = UrlConnectionFetcher(),
    private val store: UpdateStore,
    private val repository: String = REPOSITORY,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Latest(val manifest: ReleaseManifest, val fromCache: Boolean)

    private fun url(path: String) = "https://github.com/$repository/releases/$path"

    private val unreachable
        get() = DeviceError("network", "Could not reach GitHub to look for updates. Check the phone's internet connection.")

    /**
     * The newest stable release's manifest, or with [includePrereleases] the newest release of any kind
     * (`releases/latest` skips pre-releases, so that one is found in the releases list). Within the minimum
     * interval of a successful check it answers from the saved copy without touching the network; [force]
     * always asks, and changing [includePrereleases] clears the window. A "not modified" answer from GitHub
     * keeps the saved copy.
     */
    suspend fun latest(force: Boolean = false, includePrereleases: Boolean = false): Latest {
        val last = store.lastCheck
        val saved = store.manifest
        if (!force && last != null && now() - last < MINIMUM_INTERVAL_MS && saved != null) {
            ReleaseManifest.parse(saved)?.let { return Latest(it, fromCache = true) }
        }
        if (includePrereleases) {
            val source = releases(includePrereleases = true).firstOrNull { it.manifestUrl != null }?.manifestUrl
            if (source != null) {
                val found = fetchOrFail(source)
                val parsed = (if (found.status == 200) ReleaseManifest.parse(found.data) else null)
                    ?: throw DeviceError("network", "That release has no usable firmware manifest.")
                store.manifest = found.data
                store.etag = null
                store.lastCheck = now()
                return Latest(parsed, fromCache = false)
            }
        }
        val response = fetchOrFail(url("latest/download/manifest.json"), if (saved == null) null else store.etag)
        if (response.status == 304 && saved != null) {
            ReleaseManifest.parse(saved)?.let {
                store.lastCheck = now()
                return Latest(it, fromCache = true)
            }
        }
        val manifest = if (response.status == 200) ReleaseManifest.parse(response.data) else null
        if (manifest == null) {
            throw DeviceError("network", "GitHub did not have a firmware release to offer (status ${response.status}).")
        }
        store.manifest = response.data
        store.etag = response.etag
        store.lastCheck = now()
        return Latest(manifest, fromCache = false)
    }

    /** Releases, newest first, for the Advanced list. Pre-releases only when asked. */
    suspend fun releases(includePrereleases: Boolean): List<Release> {
        val api = "https://api.github.com/repos/$repository/releases?per_page=30"
        val response = fetchOrFail(api)
        val list = try {
            if (response.status == 200) Json.parseToJsonElement(String(response.data, Charsets.UTF_8)).jsonArray else null
        } catch (_: Exception) {
            null
        } ?: throw DeviceError("network", "Could not read the list of releases from GitHub (status ${response.status}).")
        val all = list.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if ((item["draft"] as? JsonPrimitive)?.boolean == true) return@mapNotNull null
            val tag = (item["tag_name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val assets = (item["assets"] as? JsonArray) ?: JsonArray(emptyList())
            val manifest = assets.map { it.jsonObject }
                .firstOrNull { it["name"]?.jsonPrimitive?.content == "manifest.json" }
                ?.get("browser_download_url")?.jsonPrimitive?.content
            Release(tag, (item["prerelease"] as? JsonPrimitive)?.boolean ?: false, manifest)
        }
        return all.filter { includePrereleases || !it.prerelease }
    }

    suspend fun manifest(release: Release): ReleaseManifest {
        val response = fetchOrFail(release.manifestUrl ?: url("download/${release.tag}/manifest.json"))
        return (if (response.status == 200) ReleaseManifest.parse(response.data) else null)
            ?: throw DeviceError("network", "That release has no usable firmware manifest.")
    }

    /** Download an image and check its size and SHA-256 against the manifest. */
    suspend fun download(image: ReleaseManifest.Image, version: String): ByteArray {
        val file = image.file
        val size = image.size
        val sha = image.sha256
        if (file == null || size == null || sha == null) throw DeviceError("network", "The release lists no file to download.")
        val response = fetchOrFail(url("download/v$version/$file"))
        if (response.status != 200) {
            throw DeviceError("network", "GitHub did not have the firmware file (status ${response.status}).")
        }
        if (response.data.size != size || sha256Hex(response.data) != sha) {
            throw DeviceError("bad_download", "The downloaded firmware didn't match the release. Try again.")
        }
        return response.data
    }

    private suspend fun fetchOrFail(url: String, etag: String? = null): HttpResult = try {
        fetcher.fetch(url, etag)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw unreachable
    }

    companion object {
        const val REPOSITORY = "nprail/kairos"

        /** The newest release is looked up at most this often (the Check button ignores it). */
        const val MINIMUM_INTERVAL_MS = 10 * 60 * 1000L

        fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()
    }
}

/**
 * Downloaded images, kept on the phone so an update can be installed with no signal. They are named by
 * their SHA-256, and only the most recent few are kept.
 */
class FirmwareCache(private val directory: File, private val keep: Int = 2) {
    init {
        directory.mkdirs()
    }

    private fun file(sha256: String) = File(directory, "$sha256.bin")

    /** A saved image, or null if there is none or it no longer matches (a damaged file is removed). */
    fun image(sha256: String, size: Int): ByteArray? {
        val f = file(sha256)
        val data = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (data.size == size && UpdateService.sha256Hex(data) == sha256) return data
        f.delete()
        return null
    }

    fun store(data: ByteArray, sha256: String) {
        val f = file(sha256)
        runCatching { f.writeBytes(data) }
        f.setLastModified(System.currentTimeMillis())
        trim()
    }

    private fun trim() {
        val files = directory.listFiles { f -> f.extension == "bin" }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep).forEach { it.delete() }
    }
}
