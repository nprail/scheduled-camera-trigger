package com.noahprail.kairos

import android.app.Application
import com.noahprail.kairos.protocol.UpdateService
import com.noahprail.kairos.protocol.FirmwareCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noahprail.kairos.ble.BleCentral
import com.noahprail.kairos.ble.BleDeviceConnection
import com.noahprail.kairos.ble.BluetoothState
import com.noahprail.kairos.ble.DiscoveredDevice
import com.noahprail.kairos.ble.SecretStore
import com.noahprail.kairos.protocol.AuthProof
import com.noahprail.kairos.protocol.CameraAction
import com.noahprail.kairos.protocol.CameraConfig
import com.noahprail.kairos.protocol.ClockSync
import com.noahprail.kairos.protocol.DeviceClient
import com.noahprail.kairos.protocol.DeviceError
import com.noahprail.kairos.protocol.DeviceEvent
import com.noahprail.kairos.protocol.DeviceInfo
import com.noahprail.kairos.protocol.PROTOCOL_VERSION
import com.noahprail.kairos.protocol.Job
import com.noahprail.kairos.protocol.LogEntry
import com.noahprail.kairos.protocol.StatusValue
import com.noahprail.kairos.protocol.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Connection state machine: disconnected → connecting → needsPairing → ready. */
sealed interface Link {
    data object Disconnected : Link
    data class Connecting(val name: String) : Link
    data class NeedsPairing(val reason: String?) : Link
    data object Ready : Link
}

/** Owns the connection and everything shown on screen. Screens read it and call it. */
class AppModel(application: Application) : AndroidViewModel(application) {
    // region Observable state

    var link by mutableStateOf<Link>(Link.Disconnected)
    var bluetooth by mutableStateOf(BluetoothState.UNKNOWN)
    var devices by mutableStateOf<List<DiscoveredDevice>>(emptyList())

    var info by mutableStateOf<DeviceInfo?>(null)
    var live by mutableStateOf<StatusValue?>(null)
    var config by mutableStateOf<CameraConfig?>(null)
    var jobs by mutableStateOf<List<Job>>(emptyList())
    var logs by mutableStateOf<List<LogEntry>>(emptyList())
    var clock by mutableStateOf<ClockSync?>(null)

    /** The Status revision of the config this model last fetched. */
    private var configRev: Long? = null

    var firmware by mutableStateOf(FirmwareState())

    /** Keep the screen on (set during a firmware transfer). */
    var keepScreenOn by mutableStateOf(false)

    /** Bumped when an update setting changes, so the screen reads the new value. */
    internal var settingsVersion by mutableStateOf(0)

    var testSteps by mutableStateOf<List<String>>(emptyList())
    var testRunning by mutableStateOf(false)
    var working by mutableStateOf(false)

    /** Last error to show the user. */
    var banner by mutableStateOf<String?>(null)

    /** Which sub-screen of the connected device is showing: main, config or logs. */
    var route by mutableStateOf("main")

    /** True when the debug demo fixtures are driving the screens (no Bluetooth). */
    var demo by mutableStateOf(false)
        internal set

    // endregion

    // region Plumbing

    private val secrets = SecretStore(application)
    internal val central = BleCentral(application, viewModelScope)
    internal var connection: BleDeviceConnection? = null
    internal var client: DeviceClient? = null
    internal var currentId: String? = null
    private val listeners = mutableListOf<kotlinx.coroutines.Job>()

    // Firmware updates (AppModelFirmware.kt)
    internal val updateStore = PrefsUpdateStore(application)
    internal val updateService = UpdateService(store = updateStore)
    internal val firmwareCache = FirmwareCache(java.io.File(application.cacheDir, "Firmware"))
    internal var installJob: kotlinx.coroutines.Job? = null
    internal var reconnectJob: kotlinx.coroutines.Job? = null
    internal var installPhase = InstallPhase.NONE
    internal var oldVersion: String? = null

    init {
        bluetooth = central.state.value
        central.state.onEach { state ->
            if (demo) return@onEach
            bluetooth = state
            if (state == BluetoothState.POWERED_ON && link == Link.Disconnected) central.startScan()
            // switching Bluetooth off does not always call back for an open connection: treat it as a lost link
            if (state != BluetoothState.POWERED_ON && link != Link.Disconnected) connectionLost()
        }.launchIn(viewModelScope)
        central.devices.onEach { if (!demo) devices = it }.launchIn(viewModelScope)
    }

    override fun onCleared() {
        listeners.forEach { it.cancel() }
        connection?.close()
        central.close()
    }

    fun isPaired(device: DiscoveredDevice): Boolean {
        val id = device.deviceId ?: return false
        return secrets.load(id) != null
    }

    // endregion

    /** Leave the debug demo and go back to the real Bluetooth stack. */
    fun exitDemo() {
        if (!demo) return
        demo = false
        teardown()
        banner = null
        bluetooth = central.state.value
        devices = central.devices.value
        central.refreshState()
        startScanning()
    }

    // region Scanning and connecting

    /** Re-check Bluetooth permissions and power, for example after the permission dialog. */
    fun refreshBluetooth() {
        if (!demo) central.refreshState()
    }

    fun startScanning() {
        if (demo) return
        if (bluetooth == BluetoothState.POWERED_ON) central.startScan()
    }

    fun stopScanning() {
        if (!demo) central.stopScan()
    }

    fun connect(device: DiscoveredDevice) {
        if (link != Link.Disconnected || demo) return
        link = Link.Connecting(device.name)
        banner = null
        central.stopScan()

        viewModelScope.launch {
            try {
                val connection = central.connect(device.id)
                this@AppModel.connection = connection
                currentId = device.id
                connection.onDisconnect = { viewModelScope.launch { if (currentId == device.id) connectionLost() } }

                val info = connection.readDeviceInfo()
                if (info.v != PROTOCOL_VERSION) {
                    throw DeviceError("unsupported", "This device uses protocol v${info.v}. Update the app.")
                }
                this@AppModel.info = info
                connection.ensureFirmwareCharacteristic(info)

                val client = DeviceClient(connection, viewModelScope)
                this@AppModel.client = client
                listen(client, connection)

                val hello = client.hello()
                val secret = if (hello.paired) secrets.load(info.id) else null
                if (hello.paired && secret != null) {
                    try {
                        client.authenticate(secret, hello)
                        becomeReady()
                    } catch (e: DeviceError) {
                        if (e.code != "unauthorized") throw e
                        link = Link.NeedsPairing(e.message)
                    }
                } else {
                    link = Link.NeedsPairing(if (hello.paired) null else "This device hasn't been paired with a phone yet.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    /** Create a pairing key and store it on the device (the button must have been pressed). */
    fun pair() {
        val client = client ?: return
        val info = info ?: return
        working = true
        viewModelScope.launch {
            try {
                val secret = AuthProof.randomBytes(16)
                client.pair(secret)
                secrets.save(secret, info.id)
                becomeReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                link = Link.NeedsPairing(describe(e))
            } finally {
                working = false
            }
        }
    }

    fun disconnect() {
        if (demo) {
            teardown()
            return
        }
        connection?.close()
        teardown()
        central.startScan()
    }

    /** Forget the stored key. The device's button re-opens pairing. */
    fun forgetPairing() {
        info?.id?.let { secrets.delete(it) }
        disconnect()
    }

    internal suspend fun becomeReady() {
        val client = client ?: return
        val connection = connection ?: return

        clock = runCatching { client.syncClock() }.getOrNull()
        runCatching { connection.readStatus() }.getOrNull()?.let { live = StatusValue.parse(it) }
        val fetched = client.config()
        config = fetched
        configRev = live?.configRev
        jobs = client.jobs(fetched)
        link = Link.Ready

        checkForUpdatesQuietly()
        loadLogs()
    }

    private suspend fun loadLogs() {
        val client = client ?: return
        val all = mutableListOf<LogEntry>()
        var after = 0
        for (page in 0 until 12) {
            val result = runCatching { client.logs(after = after, limit = 100) }.getOrNull() ?: break
            all += result.entries
            after = result.last
            if (!result.more) break
        }
        logs = all.takeLast(500)
        runCatching { client.subscribeLogs(true, after = logs.lastOrNull()?.seq ?: after) }
    }

    // endregion

    // region Live updates

    internal fun listen(client: DeviceClient, connection: BleDeviceConnection) {
        listeners.forEach { it.cancel() }
        listeners.clear()
        listeners += client.events.onEach { handle(it) }.launchIn(viewModelScope)
        listeners += connection.statusUpdates.onEach { handleStatus(it) }.launchIn(viewModelScope)
    }

    private fun handleStatus(data: ByteArray) {
        val value = StatusValue.parse(data) ?: return
        live = value

        // Status carries everything the screens show; only a new config revision needs a fetch.
        val known = configRev ?: return
        if (link != Link.Ready || known == value.configRev) return
        viewModelScope.launch { refreshSchedule() }
    }

    private fun handle(event: DeviceEvent) {
        when (event) {
            is DeviceEvent.Job -> viewModelScope.launch { refreshJobs() }
            is DeviceEvent.Config -> viewModelScope.launch { refreshSchedule() }
            is DeviceEvent.Test -> {
                val t = event.event
                if (t.step == "done") {
                    testRunning = false
                    testSteps += if (t.ok) "Done" else "Failed: ${t.error ?: "unknown error"}"
                    if (!t.ok) banner = t.error ?: "The test failed."
                } else {
                    testSteps += t.step.replaceFirstChar { it.uppercase() }
                }
            }
            is DeviceEvent.Log -> {
                if (logs.any { it.seq == event.entry.seq }) return
                logs = (logs + event.entry).takeLast(500)
            }
        }
    }

    private suspend fun refreshJobs() {
        val current = config ?: return
        runCatching { client?.jobs(current) }.getOrNull()?.let { jobs = it }
    }

    private suspend fun refreshSchedule() {
        runCatching { client?.config() }.getOrNull()?.let {
            config = it
            configRev = live?.configRev
        }
        refreshJobs()
    }

    /** The name of the attempt that owns the next event. */
    val nextAttemptName: String?
        get() = live?.nextAttempt?.let { config?.attempts?.getOrNull(it)?.name }

    // endregion

    // region Actions

    private fun perform(work: suspend (DeviceClient) -> Unit) {
        val client = client ?: return
        viewModelScope.launch {
            try {
                work(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                banner = describe(e)
            }
        }
    }

    fun runTest() {
        testSteps = emptyList()
        testRunning = true
        perform { client ->
            try {
                client.runTest()
            } catch (e: Throwable) {
                testRunning = false
                throw e
            }
        }
    }

    fun camera(action: CameraAction) {
        perform { client ->
            client.camera(action)
            runCatching { connection?.readStatus() }.getOrNull()?.let { live = StatusValue.parse(it) }
        }
    }

    fun syncClock() {
        perform { client -> clock = client.syncClock(threshold = 0.0) }
    }

    /** Returns null on success, or an error message to show in the editor. */
    suspend fun saveConfig(draft: CameraConfig): String? {
        val client = client ?: return "Not connected."
        return try {
            client.setConfig(draft)
            refreshSchedule()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            describe(e)
        }
    }

    // endregion

    // region Errors and teardown

    internal fun stopListening() {
        listeners.forEach { it.cancel() }
        listeners.clear()
    }

    internal fun fail(error: Throwable) {
        val message = describe(error)
        connection?.close()
        teardown()
        banner = message
        central.startScan()
    }

    internal fun connectionLost() {
        if (installPhase == InstallPhase.RESTARTING) {
            reconnectAfterUpdate() // expected: the device restarts to run the new firmware
            return
        }
        val duringUpdate = installPhase == InstallPhase.TRANSFERRING
        teardown()
        banner = if (duringUpdate) {
            "The connection was lost during the update. The device keeps what it received for 2 minutes: connect again and install the same firmware to continue."
        } else {
            "The connection to the device was lost."
        }
        central.startScan()
    }

    internal fun teardown() {
        installJob?.cancel()
        installJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        installPhase = InstallPhase.NONE
        keepScreenOn = false
        firmware = FirmwareState()
        listeners.forEach { it.cancel() }
        listeners.clear()
        client?.close()
        client = null
        connection = null
        currentId = null
        info = null
        live = null
        configRev = null
        config = null
        jobs = emptyList()
        logs = emptyList()
        clock = null
        testSteps = emptyList()
        testRunning = false
        link = Link.Disconnected
    }

    // endregion
}
