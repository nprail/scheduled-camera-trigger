package com.noahprail.kairos

import com.noahprail.kairos.ble.BluetoothState
import com.noahprail.kairos.ble.DiscoveredDevice
import com.noahprail.kairos.protocol.CameraConfig
import com.noahprail.kairos.protocol.ClockSync
import com.noahprail.kairos.protocol.DeviceDate
import com.noahprail.kairos.protocol.DeviceInfo
import com.noahprail.kairos.protocol.Job
import com.noahprail.kairos.protocol.LogEntry
import com.noahprail.kairos.protocol.StatusInfo
import com.noahprail.kairos.protocol.StatusValue
import java.time.Instant

/**
 * Debug-only fixtures so every screen can be shown without BLE hardware.
 * Launch with `--es demo <scene>`; see [scenes].
 */
val demoScenes = listOf(
    "scan", "scanning", "off", "unauthorized", "unsupported", "connecting",
    "pairing", "pairing-error", "ready", "generic", "recording", "banner",
)

fun AppModel.applyDemo(scene: String, screen: String = "main") {
    demo = true
    teardown()
    devices = emptyList()
    route = screen
    bluetooth = BluetoothState.POWERED_ON
    banner = null
    val now = Instant.now()
    fun iso(offsetSeconds: Long) = DeviceDate.string(now.plusSeconds(offsetSeconds))

    val device = DeviceInfo(v = 1, id = "A1B2C3", name = "Pad Cam", cam = "zcam", fw = "1.2.0")

    when (scene) {
        "off" -> bluetooth = BluetoothState.POWERED_OFF
        "unauthorized" -> bluetooth = BluetoothState.UNAUTHORIZED
        "unsupported" -> bluetooth = BluetoothState.UNSUPPORTED
        "scanning" -> devices = emptyList()
        "connecting" -> link = Link.Connecting("Pad Cam-A1B2C3")
        "pairing", "pairing-error" -> {
            info = device
            link = Link.NeedsPairing(
                if (scene == "pairing") "This device hasn't been paired with a phone yet."
                else "Press the button on the device, then try again within 2 minutes.",
            )
        }
        "ready", "generic", "recording", "banner" -> {
            val recording = scene == "recording"
            val generic = scene == "generic"
            info = if (generic) device.copy(cam = "generic") else device
            clock = ClockSync(offset = 0.4, applied = true)
            live = StatusValue(
                flags = if (recording) 0b1011 else 0b1010,
                phase = if (recording) StatusValue.Phase.RECORDING else StatusValue.Phase.AWAKE,
                configRev = 7,
                nextEventAt = now.epochSecond + if (recording) 1800 else 5400,
                nextEventType = if (recording) StatusValue.EventType.STOP else StatusValue.EventType.WAKE,
                attemptsRemaining = 2,
            )
            status = StatusInfo(
                phase = if (recording) "recording" else "awake",
                recording = recording,
                cameraReady = true,
                configRev = 7,
                next = StatusInfo.Next(
                    type = if (recording) "stop" else "start",
                    at = iso(if (recording) 1800 else 5400),
                    attempt = "Sunday 10:00",
                ),
            )
            config = CameraConfig(
                deviceId = "A1B2C3", configRev = 7, name = "Pad Cam",
                cameraType = if (generic) "generic" else "zcam",
                wakeUpTimeout = 120.0, startBefore = 60.0, endAfter = 300.0,
                logFile = "camera.log", buttonGpioPort = 17,
                generic = CameraConfig.Generic(releaseGpioPin = 23, focusGpioPin = 24),
                zcam = CameraConfig.Zcam(cameraIp = "192.168.1.50"),
                attempts = listOf(
                    CameraConfig.Attempt("Sunday 10:00", iso(5400)),
                    CameraConfig.Attempt("Sunday 12:00", iso(12600)),
                ),
            )
            fun job(id: String, name: String, at: Long, state: String) = Job(
                id = id, name = name, camera = if (generic) "generic" else "zcam",
                wakeUpTime = iso(at - 2100), recordTime = iso(at - 60),
                launchTime = iso(at), stopTime = iso(at + 1800), state = state,
            )
            jobs = listOf(
                job("1", "Sunday 10:00", 5400, if (recording) "active" else "pending"),
                job("2", "Sunday 12:00", 12600, "pending"),
                job("0", "Sunday 08:00", -9000, "complete"),
            )
            logs = listOf(
                LogEntry(1, iso(-300), "boot", "Kairos started"),
                LogEntry(2, iso(-240), "ble", "Phone connected"),
                LogEntry(3, iso(-120), "camera", "Camera woke up"),
                LogEntry(4, iso(-60), "camera", "Failed to reach camera, retrying"),
            )
            link = Link.Ready
            if (scene == "banner") banner = "The connection to the device was lost."
        }
        else -> { // "scan"
            val t = android.os.SystemClock.elapsedRealtime()
            devices = listOf(
                DiscoveredDevice("AA:BB:CC:DD:EE:01", "Pad Cam-A1B2C3", -52, t),
                DiscoveredDevice("AA:BB:CC:DD:EE:02", "Chapel-D4E5F6", -78, t),
            )
        }
    }
}
