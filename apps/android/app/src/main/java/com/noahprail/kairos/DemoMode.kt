package com.noahprail.kairos

import com.noahprail.kairos.ble.BluetoothState
import com.noahprail.kairos.ble.DiscoveredDevice
import com.noahprail.kairos.protocol.CameraConfig
import com.noahprail.kairos.protocol.Chip
import com.noahprail.kairos.protocol.ReleaseManifest
import com.noahprail.kairos.protocol.ClockSync
import com.noahprail.kairos.protocol.DeviceInfo
import com.noahprail.kairos.protocol.Job
import com.noahprail.kairos.protocol.LogEntry
import com.noahprail.kairos.protocol.PROTOCOL_VERSION
import com.noahprail.kairos.protocol.StatusValue
import java.time.Instant

/**
 * Debug-only fixtures so every screen can be shown without BLE hardware.
 * Launch with `--es demo <scene>`; see [scenes].
 */
val demoScenes = listOf(
    "scan", "scanning", "off", "unauthorized", "unsupported", "connecting",
    "pairing", "pairing-error", "ready", "generic", "recording", "banner",
    "firmware", "firmware-progress", "firmware-failed",
)

fun AppModel.applyDemo(scene: String, screen: String = "main") {
    demo = true
    teardown()
    devices = emptyList()
    route = screen
    bluetooth = BluetoothState.POWERED_ON
    banner = null
    val now = Instant.now()

    val device = DeviceInfo(
        v = PROTOCOL_VERSION, id = "A1B2C3", name = "Pad Cam", cam = "zcam", fw = "1.2.0",
        caps = 1, chip = Chip.ESP32S3, slot = 0x1F0000,
    )

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
        "ready", "generic", "recording", "banner", "firmware", "firmware-progress", "firmware-failed" -> {
            val recording = scene == "recording"
            val generic = scene == "generic"
            // the generic scene is a unit without the Ethernet module: no Z CAM option
            info = if (generic) device.copy(cam = "generic", cams = listOf("generic")) else device
            clock = ClockSync(offset = 0.4, applied = true)
            live = StatusValue(
                flags = if (recording) 0b1011 else 0b1010,
                phase = if (recording) StatusValue.Phase.RECORDING else StatusValue.Phase.AWAKE,
                configRev = 7,
                nextEventAt = now.epochSecond + if (recording) 1800 else 5400,
                nextEventType = if (recording) StatusValue.EventType.STOP else StatusValue.EventType.WAKE,
                attemptsRemaining = 2,
                nextAttempt = 0,
            )
            val fetched = CameraConfig(
                name = "Pad Cam",
                cameraType = if (generic) "generic" else "zcam",
                wakeUpTimeout = 120.0, startBefore = 60.0, endAfter = 300.0,
                zcam = CameraConfig.Zcam(cameraIp = "192.168.1.50"),
                attempts = listOf(
                    CameraConfig.Attempt.create("Sunday 10:00", now.plusSeconds(5400)),
                    CameraConfig.Attempt.create("Sunday 12:00", now.plusSeconds(12600)),
                    CameraConfig.Attempt.create("Sunday 08:00", now.minusSeconds(9000)),
                ),
            )
            config = fetched
            val states = listOf(if (recording) "active" else "pending", "pending", "complete")
            jobs = fetched.attempts.mapIndexed { i, a -> Job(i, a.name, a.instant, states[i]) }
            fun line(seq: Int, at: Long, ns: String, msg: String) = LogEntry(seq, now.epochSecond + at, ns, msg)
            logs = listOf(
                line(1, -300, "boot", "Kairos started"),
                line(2, -240, "ble", "Phone connected"),
                line(3, -120, "camera", "Camera woke up"),
                line(4, -60, "camera", "Failed to reach camera, retrying"),
            )
            link = Link.Ready
            if (scene == "banner") banner = "The connection to the device was lost."
            if (scene.startsWith("firmware")) {
                // idle, with the next event far enough away that an update could start
                live = live!!.copy(phase = StatusValue.Phase.IDLE, nextEventAt = now.epochSecond + 5400, nextEventType = StatusValue.EventType.WAKE)
                val image = ReleaseManifest.Image("kairos-esp32s3-1.3.0-app.bin", 1_187_344, "a".repeat(64), "b".repeat(128))
                var state = FirmwareState(offer = FirmwareOffer("1.3.0", "Fixes the Z CAM retry and adds firmware updates.", image))
                if (scene == "firmware-progress") {
                    state = state.copy(
                        prepared = PreparedFirmware("Version 1.3.0", "1.3.0", image.file, ByteArray(1_187_344), ByteArray(64), "a".repeat(64)),
                        stage = FirmwareStage.Sending(0.42),
                    )
                } else if (scene == "firmware-failed") {
                    state = state.copy(stage = FirmwareStage.Failed("The firmware's signature isn't valid for this device."))
                }
                firmware = state
            }
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
