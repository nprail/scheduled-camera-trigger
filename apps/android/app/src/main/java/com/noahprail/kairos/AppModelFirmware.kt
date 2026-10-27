package com.noahprail.kairos

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.noahprail.kairos.ble.DiscoveredDevice
import com.noahprail.kairos.ble.SecretStore
import com.noahprail.kairos.protocol.Chip
import com.noahprail.kairos.protocol.DeviceClient
import com.noahprail.kairos.protocol.DeviceError
import com.noahprail.kairos.protocol.DeviceInfo
import com.noahprail.kairos.protocol.FirmwareCheck
import com.noahprail.kairos.protocol.FirmwareImage
import com.noahprail.kairos.protocol.FirmwareUploader
import com.noahprail.kairos.protocol.FirmwareVersion
import com.noahprail.kairos.protocol.PROTOCOL_VERSION
import com.noahprail.kairos.protocol.Release
import com.noahprail.kairos.protocol.ReleaseManifest
import com.noahprail.kairos.protocol.StatusValue
import com.noahprail.kairos.protocol.UpdateService
import com.noahprail.kairos.protocol.UpdateStore
import com.noahprail.kairos.protocol.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Updating the device's firmware over Bluetooth (docs/ota-plan.md). An update is always the user's choice: the
// app may look for a newer release when it connects, but that only ever shows a badge.

sealed interface FirmwareStage {
    data object Idle : FirmwareStage
    data object Checking : FirmwareStage
    data object Downloading : FirmwareStage
    data class Sending(val fraction: Double) : FirmwareStage
    data object Restarting : FirmwareStage
    data object Reconnecting : FirmwareStage
    data class Done(val version: String) : FirmwareStage
    data class RolledBack(val version: String) : FirmwareStage
    data class Failed(val message: String) : FirmwareStage
}

/** A release the check found. */
data class FirmwareOffer(val version: String, val notes: String?, val image: ReleaseManifest.Image)

/** An image ready to send. */
class PreparedFirmware(
    val title: String,
    /** The version in the image's tag, when it has one. */
    val version: String?,
    val fileName: String?,
    val data: ByteArray,
    /** 64 bytes, or empty when unsigned. */
    val signature: ByteArray,
    val sha256: String,
) {
    val isSigned: Boolean get() = signature.size == 64

    fun withSignature(sig: ByteArray) = PreparedFirmware(title, version, fileName, data, sig, sha256)
}

data class FirmwareState(
    val stage: FirmwareStage = FirmwareStage.Idle,
    val offer: FirmwareOffer? = null,
    /** What the last check said, for the Firmware screen. */
    val message: String? = null,
    val prepared: PreparedFirmware? = null,
    val releases: List<Release>? = null,
    val loadingReleases: Boolean = false,
)

enum class InstallPhase { NONE, TRANSFERRING, RESTARTING }

/** The update settings and the last check, in app-private preferences. */
class PrefsUpdateStore(context: Context) : UpdateStore {
    private val prefs = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    override var lastCheck: Long?
        get() = if (prefs.contains("lastCheck")) prefs.getLong("lastCheck", 0) else null
        set(value) = prefs.edit().apply { if (value == null) remove("lastCheck") else putLong("lastCheck", value) }.apply()

    override var manifest: ByteArray?
        get() = prefs.getString("manifest", null)?.toByteArray(Charsets.UTF_8)
        set(value) = prefs.edit().apply { if (value == null) remove("manifest") else putString("manifest", String(value, Charsets.UTF_8)) }.apply()

    override var etag: String?
        get() = prefs.getString("etag", null)
        set(value) = prefs.edit().putString("etag", value).apply()

    override var checkOnConnect: Boolean
        get() = prefs.getBoolean("checkOnConnect", true)
        set(value) = prefs.edit().putBoolean("checkOnConnect", value).apply()

    override var includePrereleases: Boolean
        get() = prefs.getBoolean("includePrereleases", false)
        set(value) = prefs.edit().putBoolean("includePrereleases", value).apply()
}

// region State

val AppModel.canUpdate: Boolean get() = info?.canUpdate == true

val AppModel.firmwareBusy: Boolean
    get() = when (firmware.stage) {
        FirmwareStage.Idle, is FirmwareStage.Done, is FirmwareStage.RolledBack, is FirmwareStage.Failed -> false
        else -> true
    }

/** Why an install cannot start now, if it cannot (the device refuses too, but say it before sending). */
val AppModel.installBlocker: String?
    get() {
        if ((live?.phase ?: StatusValue.Phase.IDLE) != StatusValue.Phase.IDLE) return "The device must be idle to take an update."
        live?.nextEventDate?.let {
            if (it.epochSecond - java.time.Instant.now().epochSecond < 15 * 60) return "An event is scheduled within 15 minutes. Try again after it."
        }
        return null
    }

var AppModel.checkOnConnectEnabled: Boolean
    get() = settingsVersion.let { updateStore.checkOnConnect }
    set(value) {
        updateStore.checkOnConnect = value
        settingsVersion++
    }

var AppModel.includePrereleases: Boolean
    get() = settingsVersion.let { updateStore.includePrereleases }
    set(value) {
        updateStore.includePrereleases = value
        settingsVersion++
        firmware = firmware.copy(releases = null)
    }

// endregion

// region Looking for updates

/** Called when the device becomes ready. It never blocks, never shows an error, and only sets the offer. */
internal fun AppModel.checkForUpdatesQuietly() {
    if (!canUpdate || !checkOnConnectEnabled) return
    viewModelScope.launch { runCheck(force = false, report = false) }
}

/** The Check for updates button: ignores the 10-minute window and says what it found. */
fun AppModel.checkForUpdates() {
    if (!canUpdate || firmwareBusy) return
    viewModelScope.launch { runCheck(force = true, report = true) }
}

private suspend fun AppModel.runCheck(force: Boolean, report: Boolean) {
    val chip = info?.chip ?: return
    val fw = info?.fw ?: return
    if (report) firmware = firmware.copy(stage = FirmwareStage.Checking, message = null)
    try {
        val manifest = updateService.latest(force).manifest
        val result = manifest.assess(chip, fw, PROTOCOL_VERSION)
        if (info?.fw != fw) return // the device changed while we were looking
        val image = result.image
        val version = manifest.version
        firmware = firmware.copy(
            offer = if (result.status == ReleaseManifest.Status.AVAILABLE && image != null && version != null) {
                FirmwareOffer(version, manifest.notes, image)
            } else {
                null
            },
            message = if (report) checkText(result.status, manifest, fw, chip) else firmware.message,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (report) firmware = firmware.copy(message = describe(e))
    }
    if (report && firmware.stage == FirmwareStage.Checking) firmware = firmware.copy(stage = FirmwareStage.Idle)
}

fun checkText(status: ReleaseManifest.Status, manifest: ReleaseManifest, deviceFw: String, chip: Chip): String {
    val v = manifest.version ?: "?"
    return when (status) {
        ReleaseManifest.Status.AVAILABLE -> "Version $v is available."
        ReleaseManifest.Status.UP_TO_DATE -> "Up to date ($deviceFw)."
        ReleaseManifest.Status.NEWER_INSTALLED -> "This device runs a newer version ($deviceFw) than the latest release ($v)."
        ReleaseManifest.Status.UNKNOWN_CURRENT ->
            "This device runs a development build ($deviceFw), so it can't be compared with the latest release ($v)."
        ReleaseManifest.Status.APP_TOO_OLD -> "Version $v needs a newer version of this app. Update the app first."
        ReleaseManifest.Status.NEEDS_USB -> "Version $v can't be installed over Bluetooth on this device. It needs a USB flash."
        ReleaseManifest.Status.UNAVAILABLE -> "The latest release has no firmware for this device (${chip.title})."
        ReleaseManifest.Status.INVALID -> "The release information from GitHub could not be read."
    }
}

// endregion

// region Getting an image

/** Download the offered release (or take it from the cache) and make it ready to install. */
fun AppModel.downloadOffer() {
    val offer = firmware.offer ?: return
    if (firmwareBusy) return
    viewModelScope.launch { prepare(offer.version, offer.image) }
}

private suspend fun AppModel.prepare(version: String, image: ReleaseManifest.Image) {
    val sha = image.sha256 ?: return
    val size = image.size ?: return
    val signature = image.signature ?: return
    firmware = firmware.copy(message = null)
    try {
        var data = withContext(Dispatchers.IO) { firmwareCache.image(sha, size) }
        if (data == null) {
            firmware = firmware.copy(stage = FirmwareStage.Downloading)
            data = updateService.download(image, version)
            val bytes = data
            withContext(Dispatchers.IO) { firmwareCache.store(bytes, sha) }
        }
        val slot = info?.slot ?: 0
        if (slot > 0 && data.size > slot) throw DeviceError("too_large", "That firmware is too large for this device.")
        firmware = firmware.copy(
            prepared = PreparedFirmware("Version $version", version, image.file, data, signature, sha),
            stage = FirmwareStage.Idle,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        firmware = firmware.copy(stage = FirmwareStage.Idle, message = describe(e))
    }
}

/** Releases for the Advanced list. */
fun AppModel.loadReleases() {
    if (firmware.releases != null || firmware.loadingReleases) return
    firmware = firmware.copy(loadingReleases = true)
    viewModelScope.launch {
        try {
            val list = updateService.releases(includePrereleases)
            firmware = firmware.copy(releases = list, loadingReleases = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            firmware = firmware.copy(loadingReleases = false, message = describe(e))
        }
    }
}

/** Pick an older or other release from the list, check it against this device, and download it. */
fun AppModel.chooseRelease(release: Release) {
    val chip = info?.chip ?: return
    val fw = info?.fw ?: return
    if (firmwareBusy) return
    firmware = firmware.copy(stage = FirmwareStage.Checking, message = null)
    viewModelScope.launch {
        try {
            val manifest = updateService.manifest(release)
            val result = manifest.assess(chip, fw, PROTOCOL_VERSION)
            when (result.status) {
                ReleaseManifest.Status.INVALID, ReleaseManifest.Status.UNAVAILABLE,
                ReleaseManifest.Status.APP_TOO_OLD, ReleaseManifest.Status.NEEDS_USB ->
                    firmware = firmware.copy(stage = FirmwareStage.Idle, message = checkText(result.status, manifest, fw, chip))
                else -> {
                    val image = result.image
                    val version = manifest.version
                    if (image == null || version == null) firmware = firmware.copy(stage = FirmwareStage.Idle)
                    else prepare(version, image)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            firmware = firmware.copy(stage = FirmwareStage.Idle, message = describe(e))
        }
    }
}

private class PickedFile(val name: String, val data: ByteArray)

private fun Context.readPicked(uri: Uri): PickedFile? = try {
    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "firmware.bin"
    val data = contentResolver.openInputStream(uri)?.use { it.readBytes() }
    data?.let { PickedFile(name, it) }
} catch (_: Exception) {
    null
}

/** Files from the picker: a firmware `.bin`, and optionally its `.sig`. */
fun AppModel.importFirmwareFiles(uris: List<Uri>) {
    val chip = info?.chip ?: return
    if (firmwareBusy) return
    firmware = firmware.copy(message = null)
    viewModelScope.launch {
        val files = withContext(Dispatchers.IO) { uris.map { it to getApplication<Application>().readPicked(it) } }
        var image: PickedFile? = null
        var signature: ByteArray? = null
        for ((uri, file) in files) {
            if (file == null) {
                firmware = firmware.copy(message = "Could not read ${uri.lastPathSegment ?: "that file"}.")
                return@launch
            }
            if (file.name.lowercase().endsWith(".sig") || file.data.size <= 200) {
                signature = FirmwareImage.signature(file.data)
                if (signature == null) {
                    firmware = firmware.copy(message = "${file.name} isn't a valid signature file.")
                    return@launch
                }
            } else {
                image = file
            }
        }
        val picked = image
        val current = firmware.prepared
        when {
            picked != null -> {
                val check = FirmwareImage.check(picked.data, chip)
                if (check !is FirmwareCheck.Ok) {
                    firmware = firmware.copy(message = check.message)
                    return@launch
                }
                val slot = info?.slot ?: 0
                if (slot > 0 && picked.data.size > slot) {
                    firmware = firmware.copy(message = "That firmware is too large for this device.")
                    return@launch
                }
                val sha = withContext(Dispatchers.Default) { UpdateService.sha256Hex(picked.data) }
                firmware = firmware.copy(
                    prepared = PreparedFirmware(picked.name, check.version, picked.name, picked.data, signature ?: ByteArray(0), sha),
                )
            }
            signature != null && current != null -> firmware = firmware.copy(prepared = current.withSignature(signature))
            else -> firmware = firmware.copy(message = "Choose the firmware file (ending in -app.bin).")
        }
    }
}

fun AppModel.discardPrepared() {
    if (!firmwareBusy) firmware = firmware.copy(prepared = null)
}

fun AppModel.dismissFirmwareResult() {
    when (firmware.stage) {
        is FirmwareStage.Done, is FirmwareStage.RolledBack, is FirmwareStage.Failed -> firmware = firmware.copy(stage = FirmwareStage.Idle)
        else -> Unit
    }
}

// endregion

// region Installing

fun AppModel.install() {
    val client = client ?: return
    val connection = connection ?: return
    val prepared = firmware.prepared ?: return
    if (installJob != null || firmwareBusy || installBlocker != null) return
    oldVersion = info?.fw
    firmware = firmware.copy(stage = FirmwareStage.Sending(0.0), message = null)
    installPhase = InstallPhase.TRANSFERRING
    keepScreenOn = true
    installJob = viewModelScope.launch {
        try {
            val uploader = FirmwareUploader(client, connection)
            uploader.upload(prepared.data, prepared.signature) { p ->
                firmware = firmware.copy(stage = FirmwareStage.Sending(p.fraction))
            }
            // the device now checks the image and restarts, which drops the link: connectionLost() carries on
            firmware = firmware.copy(stage = FirmwareStage.Restarting)
            installPhase = InstallPhase.RESTARTING
            delay(20_000)
            if (installPhase == InstallPhase.RESTARTING) { // it did not restart
                installPhase = InstallPhase.NONE
                keepScreenOn = false
                firmware = firmware.copy(stage = FirmwareStage.Failed("The device accepted the update but did not restart."))
            }
        } catch (e: CancellationException) {
            if (installPhase == InstallPhase.TRANSFERRING) {
                installPhase = InstallPhase.NONE
                keepScreenOn = false
                firmware = firmware.copy(stage = FirmwareStage.Idle)
            }
            throw e
        } catch (e: Throwable) {
            if (installPhase != InstallPhase.TRANSFERRING) return@launch
            installPhase = InstallPhase.NONE
            keepScreenOn = false
            if ((e as? DeviceError)?.code == "disconnected") return@launch // connectionLost() explains it
            firmware = firmware.copy(stage = FirmwareStage.Failed(describe(e)))
        } finally {
            installJob = null
        }
    }
}

fun AppModel.cancelInstall() {
    if (firmware.stage is FirmwareStage.Sending) installJob?.cancel()
}

/** The link dropped after the device took the image: wait for it to come back on the new firmware. */
internal fun AppModel.reconnectAfterUpdate() {
    val id = info?.id ?: return
    installJob?.cancel()
    installPhase = InstallPhase.NONE
    val expected = firmware.prepared?.version
    val before = oldVersion
    stopListening()
    client?.close()
    client = null
    connection = null
    currentId = null
    firmware = firmware.copy(stage = FirmwareStage.Reconnecting)

    reconnectJob = viewModelScope.launch {
        central.startScan()
        delay(2_000)
        val deadline = System.nanoTime() + 60_000_000_000L
        var found: DiscoveredDevice? = null
        while (System.nanoTime() < deadline && found == null) {
            found = central.devices.value.firstOrNull { it.deviceId == id }
            if (found == null) delay(500)
        }
        keepScreenOn = false
        val device = found
        if (device == null) {
            fail(DeviceError("timeout", "The device didn't come back after the update. Check it, then connect again."))
            return@launch
        }
        central.stopScan()
        try {
            val conn = central.connect(device.id)
            connection = conn
            currentId = device.id
            conn.onDisconnect = { viewModelScope.launch { if (currentId == device.id) connectionLost() } }
            val newInfo: DeviceInfo = conn.readDeviceInfo()
            info = newInfo
            conn.ensureFirmwareCharacteristic(newInfo)
            val newClient = DeviceClient(conn, viewModelScope)
            client = newClient
            listen(newClient, conn)
            val hello = newClient.hello()
            val secret = if (hello.paired) SecretStore(getApplication()).load(newInfo.id) else null
            if (secret == null) {
                throw DeviceError("unauthorized", "The device rejected the pairing key. Re-pair using the device's button.")
            }
            newClient.authenticate(secret, hello)
            firmware = firmware.copy(offer = null)
            becomeReady()
            val ok = if (expected != null) expected == newInfo.fw else newInfo.fw != before
            firmware = firmware.copy(
                stage = if (ok) FirmwareStage.Done(newInfo.fw) else FirmwareStage.RolledBack(newInfo.fw),
                prepared = if (ok) null else firmware.prepared,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(e)
        }
    }
}

// endregion
