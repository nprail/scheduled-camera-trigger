@file:OptIn(ExperimentalMaterial3Api::class)

package com.noahprail.kairos.ui

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noahprail.kairos.AppModel
import com.noahprail.kairos.canUpdate
import com.noahprail.kairos.protocol.CameraAction
import com.noahprail.kairos.protocol.StatusValue
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.roundToInt

@Composable
private fun transparent() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
fun DeviceScreen(model: AppModel, onEditConfig: () -> Unit, onShowLogs: () -> Unit, onShowFirmware: () -> Unit) {
    var confirmForget by remember { mutableStateOf(false) }
    var confirmCamera by remember { mutableStateOf<CameraAction?>(null) }

    val phase = model.live?.phase ?: StatusValue.Phase.IDLE
    /** Manual camera control is refused by the device during an attempt or test. */
    val canControl = phase == StatusValue.Phase.IDLE || phase == StatusValue.Phase.RECORDING
    val title = model.info?.name?.takeIf { it.isNotEmpty() } ?: "Kairos"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = { TextButton(onClick = { model.disconnect() }) { Text("Disconnect") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusSection(model) }
            item { NextSection(model) }
            item {
                ControlSection(
                    model = model,
                    phase = phase,
                    canControl = canControl,
                    onCamera = { action ->
                        if (action == CameraAction.START || action == CameraAction.STOP) confirmCamera = action
                        else model.camera(action)
                    },
                )
            }
            item { ScheduleSection(model, onEditConfig) }
            item {
                DeviceSection(
                    model = model,
                    phase = phase,
                    onShowLogs = onShowLogs,
                    onShowFirmware = onShowFirmware,
                    onForget = { confirmForget = true },
                )
            }
        }
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "Forget this device?",
            message = "To pair again, press the button on the device.",
            confirmLabel = "Forget Pairing",
            destructive = true,
            onConfirm = { model.forgetPairing() },
            onDismiss = { confirmForget = false },
        )
    }
    confirmCamera?.let { action ->
        ConfirmDialog(
            title = if (action == CameraAction.START) "Start recording now?" else "Stop recording now?",
            message = null,
            confirmLabel = if (action == CameraAction.START) "Start Recording" else "Stop Recording",
            onConfirm = { model.camera(action) },
            onDismiss = { confirmCamera = null },
        )
    }
}

// region Status and next

@Composable
private fun StatusSection(model: AppModel) {
    val phase = model.live?.phase ?: StatusValue.Phase.IDLE
    val color = phaseColor(phase)
    Section(title = null) {
        ListItem(
            leadingContent = { Icon(phaseIcon(phase), null, tint = color, modifier = Modifier.size(28.dp)) },
            headlineContent = {
                Text(phaseTitle(phase).uppercase(), style = BrandText.label.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurface)
            },
            trailingContent = {
                if (model.live?.recording == true) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // the record dot: the one place signal is spent on this card
                        Icon(Icons.Filled.FiberManualRecord, null, tint = MaterialTheme.colorScheme.brand.signal, modifier = Modifier.size(14.dp))
                        Text("REC", style = BrandText.label, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            },
            colors = transparent(),
        )
        if (model.live?.cameraReady == false) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                Text(
                    "Camera not ready. Check the wiring or camera connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun NextSection(model: AppModel) {
    val live = model.live
    val date = live?.nextEventDate
    Section(title = "Next") {
        if (date != null && live != null) {
            // ticks locally once a second; it doesn't wait for the device
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1000)
                }
            }
            ListItem(
                headlineContent = { Text(eventTitle(live.nextEventType), style = MaterialTheme.typography.titleMedium) },
                supportingContent = model.nextAttemptName?.let { { Text(it) } },
                trailingContent = {
                    Text(countdown(date, now), style = BrandText.timecodeLarge)
                },
                colors = transparent(),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text("At") },
                trailingContent = { Text(date.formatDateTime(), style = BrandText.timecode.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = transparent(),
            )
        } else {
            ListItem(
                headlineContent = { Text("Nothing scheduled", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = transparent(),
            )
        }
    }
}

// endregion

// region Camera controls

@Composable
private fun ControlSection(
    model: AppModel,
    phase: StatusValue.Phase,
    canControl: Boolean,
    onCamera: (CameraAction) -> Unit,
) {
    Section(title = "Camera") {
        ListItem(
            modifier = Modifier.clickable(enabled = phase == StatusValue.Phase.IDLE && !model.testRunning) { model.runTest() },
            leadingContent = {
                Icon(
                    Icons.Filled.Verified, null,
                    tint = if (phase == StatusValue.Phase.IDLE && !model.testRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            },
            headlineContent = {
                Text(
                    "Run test",
                    color = if (phase == StatusValue.Phase.IDLE && !model.testRunning) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            },
            trailingContent = {
                if (model.testRunning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            },
            colors = transparent(),
        )
        if (model.testSteps.isNotEmpty()) {
            Text(
                model.testSteps.joinToString(" → "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlButton("Wake", Icons.Filled.WbSunny, canControl, Modifier.weight(1f)) { onCamera(CameraAction.WAKE) }
                ControlButton("Sleep", Icons.Filled.NightsStay, canControl, Modifier.weight(1f)) { onCamera(CameraAction.SLEEP) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlButton("Start", Icons.Filled.PlayCircle, canControl, Modifier.weight(1f)) { onCamera(CameraAction.START) }
                ControlButton("Stop", Icons.Filled.StopCircle, canControl, Modifier.weight(1f)) { onCamera(CameraAction.STOP) }
            }
        }
    }
}

@Composable
private fun ControlButton(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

// endregion

// region Schedule and device

@Composable
private fun ScheduleSection(model: AppModel, onEdit: () -> Unit) {
    Section(title = "Schedule") {
        if (model.jobs.isEmpty()) {
            ListItem(
                headlineContent = { Text("No attempts scheduled", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = transparent(),
            )
        }
        model.jobs.forEach { job ->
            ListItem(
                headlineContent = { Text(job.name) },
                supportingContent = { Text(job.launchDate.formatDateTime(), style = BrandText.timecode.copy(fontSize = 12.sp)) },
                trailingContent = { StatePill(job.state) },
                colors = transparent(),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
        ListItem(
            modifier = Modifier.clickable(onClick = onEdit),
            headlineContent = { Text("Edit schedule and settings") },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            colors = transparent(),
        )
    }
}

/** A small outlined tag. The state is always in the text; a filled dot marks an active attempt. */
@Composable
internal fun StatePill(state: String) {
    Surface(
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state == "active") {
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape))
            }
            Text(state.uppercase(), style = BrandText.label)
        }
    }
}

@Composable
private fun DeviceSection(
    model: AppModel,
    phase: StatusValue.Phase,
    onShowLogs: () -> Unit,
    onShowFirmware: () -> Unit,
    onForget: () -> Unit,
) {
    Section(title = "Device") {
        ListItem(
            modifier = Modifier.clickable(onClick = onShowLogs),
            headlineContent = { Text("Logs") },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            colors = transparent(),
        )
        if (model.canUpdate) {
            ListItem(
                modifier = Modifier.clickable(onClick = onShowFirmware),
                headlineContent = { Text("Firmware") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // a quiet badge: an update is the user's choice, never a prompt
                        model.firmware.offer?.let { StatePill("Update ${it.version}") }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                },
                colors = transparent(),
            )
        }
        ListItem(
            headlineContent = { Text("Clock") },
            supportingContent = { Text(clockText(model)) },
            trailingContent = {
                OutlinedButton(onClick = { model.syncClock() }, enabled = phase == StatusValue.Phase.IDLE) {
                    Text("Sync now")
                }
            },
            colors = transparent(),
        )
        model.info?.let { info ->
            InfoRow("ID", info.id)
            InfoRow("Camera", info.cam)
            InfoRow("Software", info.fw)
        }
        ListItem(
            modifier = Modifier.clickable(onClick = onForget),
            headlineContent = { Text("Forget pairing", color = MaterialTheme.colorScheme.error) },
            colors = transparent(),
        )
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Text(value, style = BrandText.timecode.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = transparent(),
    )
}

// endregion

// region Helpers

private fun phaseTitle(phase: StatusValue.Phase) = when (phase) {
    StatusValue.Phase.IDLE -> "Idle"
    StatusValue.Phase.AWAKE -> "Camera awake"
    StatusValue.Phase.RECORDING -> "Recording"
    StatusValue.Phase.TEST -> "Running test"
    StatusValue.Phase.ERROR -> "Needs attention"
}

private fun phaseIcon(phase: StatusValue.Phase) = when (phase) {
    StatusValue.Phase.IDLE -> Icons.Filled.PauseCircle
    StatusValue.Phase.AWAKE -> Icons.Filled.WbTwilight
    StatusValue.Phase.RECORDING -> Icons.Filled.FiberManualRecord
    StatusValue.Phase.TEST -> Icons.Filled.CheckCircle
    StatusValue.Phase.ERROR -> Icons.Filled.Error
}

@Composable
private fun phaseColor(phase: StatusValue.Phase): Color = when (phase) {
    StatusValue.Phase.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    // signal is kept for the REC dot, so the other states use ink and differ by icon and label
    else -> MaterialTheme.colorScheme.onSurface
}

private fun eventTitle(type: StatusValue.EventType) = when (type) {
    StatusValue.EventType.WAKE -> "Wake the camera"
    StatusValue.EventType.START -> "Start recording"
    StatusValue.EventType.STOP -> "Stop recording"
    StatusValue.EventType.NONE -> "Nothing"
}

/** T-minus notation (`T-HH:MM:SS`), never negative. */
private fun countdown(date: Instant, nowMillis: Long): String {
    val seconds = maxOf(0, Math.ceil((date.toEpochMilli() - nowMillis) / 1000.0).toInt())
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "T-%02d:%02d:%02d".format(h, m, s)
}

private fun clockText(model: AppModel): String {
    val clock = model.clock ?: return "Not checked"
    val ms = (clock.offset * 1000).roundToInt()
    return if (clock.applied) "Set from this phone (was $ms ms off)" else "In sync ($ms ms)"
}

// endregion
