@file:OptIn(ExperimentalMaterial3Api::class)

package com.noahprail.kairos.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noahprail.kairos.AppModel
import com.noahprail.kairos.FirmwareStage
import com.noahprail.kairos.PreparedFirmware
import com.noahprail.kairos.checkForUpdates
import com.noahprail.kairos.checkOnConnectEnabled
import com.noahprail.kairos.cancelInstall
import com.noahprail.kairos.chooseRelease
import com.noahprail.kairos.discardPrepared
import com.noahprail.kairos.dismissFirmwareResult
import com.noahprail.kairos.downloadOffer
import com.noahprail.kairos.firmwareBusy
import com.noahprail.kairos.importFirmwareFiles
import com.noahprail.kairos.includePrereleases
import com.noahprail.kairos.install
import com.noahprail.kairos.installBlocker
import com.noahprail.kairos.loadReleases
import com.noahprail.kairos.protocol.FirmwareVersion

@Composable
private fun transparentItem() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Firmware updates (docs/ota-plan.md). Nothing here starts on its own: every download and install is a tap. */
@Composable
fun FirmwareScreen(model: AppModel, onBack: () -> Unit) {
    var confirmingInstall by remember { mutableStateOf(false) }
    var choosingVersion by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.importFirmwareFiles(uris)
    }
    val fw = model.firmware

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Firmware") },
                navigationIcon = {
                    if (!model.firmwareBusy) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section(title = "This device") {
                    InfoRow("Version", model.info?.fw ?: "")
                    InfoRow("Chip", model.info?.chip?.title ?: "")
                }
            }
            when (val stage = fw.stage) {
                FirmwareStage.Idle, FirmwareStage.Checking -> {
                    item { UpdateSection(model, onInstall = { confirmingInstall = true }, onChooseFile = { picker.launch(arrayOf("*/*")) }) }
                    item {
                        Section(
                            title = "From a file",
                            footer = "Choose the firmware (ending in -app.bin). If it has a matching .sig file, choose both.",
                        ) {
                            ListItem(
                                modifier = Modifier.clickable { picker.launch(arrayOf("*/*")) },
                                headlineContent = { Text("Choose a file…") },
                                colors = transparentItem(),
                            )
                        }
                    }
                    item {
                        Section(title = null) {
                            ListItem(
                                modifier = Modifier.clickable { advanced = !advanced },
                                headlineContent = { Text("Advanced") },
                                colors = transparentItem(),
                            )
                            if (advanced) {
                                ListItem(
                                    headlineContent = { Text("Check for updates when connecting") },
                                    trailingContent = {
                                        Switch(checked = model.checkOnConnectEnabled, onCheckedChange = { model.checkOnConnectEnabled = it })
                                    },
                                    colors = transparentItem(),
                                )
                                ListItem(
                                    headlineContent = { Text("Include pre-releases") },
                                    trailingContent = {
                                        Switch(checked = model.includePrereleases, onCheckedChange = { model.includePrereleases = it })
                                    },
                                    colors = transparentItem(),
                                )
                                ListItem(
                                    modifier = Modifier.clickable {
                                        model.loadReleases()
                                        choosingVersion = true
                                    },
                                    headlineContent = { Text("Choose a version…") },
                                    colors = transparentItem(),
                                )
                            }
                        }
                    }
                }
                FirmwareStage.Downloading -> item { ProgressSection("Downloading…", null, cancellable = false, onCancel = {}) }
                is FirmwareStage.Sending ->
                    item { ProgressSection("Sending…", stage.fraction, cancellable = true, onCancel = { model.cancelInstall() }) }
                FirmwareStage.Restarting -> item { ProgressSection("Restarting the device…", null, cancellable = false, onCancel = {}) }
                FirmwareStage.Reconnecting -> item { ProgressSection("Reconnecting…", null, cancellable = false, onCancel = {}) }
                is FirmwareStage.Done ->
                    item { ResultSection(Icons.Filled.CheckCircle, "Updated to ${stage.version}.", "Pairing and the schedule are unchanged.") { model.dismissFirmwareResult() } }
                is FirmwareStage.RolledBack ->
                    item {
                        ResultSection(
                            Icons.AutoMirrored.Filled.Undo, "The update was rolled back.",
                            "The device restarted on its previous firmware (${stage.version}). Its log may say why.",
                        ) { model.dismissFirmwareResult() }
                    }
                is FirmwareStage.Failed ->
                    item { ResultSection(Icons.Filled.Warning, "The update didn't finish.", stage.message) { model.dismissFirmwareResult() } }
            }
            fw.message?.takeIf { !model.firmwareBusy }?.let { message ->
                item {
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
        }
    }

    if (choosingVersion) VersionDialog(model, onDismiss = { choosingVersion = false })

    if (confirmingInstall) {
        val prepared = fw.prepared
        ConfirmDialog(
            title = "Install ${prepared?.title ?: "this firmware"}?",
            message = installMessage(model, prepared),
            confirmLabel = "Install",
            destructive = true,
            onConfirm = { model.install() },
            onDismiss = { confirmingInstall = false },
        )
    }
}

@Composable
private fun UpdateSection(model: AppModel, onInstall: () -> Unit, onChooseFile: () -> Unit) {
    val fw = model.firmware
    val context = LocalContext.current
    Section(title = "Updates", footer = "Updates are always your choice. The device keeps its pairing and schedule.") {
        fw.offer?.let { offer ->
            ListItem(
                headlineContent = { Text("Version ${offer.version} is available", style = MaterialTheme.typography.titleMedium) },
                supportingContent = offer.notes?.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                colors = transparentItem(),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
        val prepared = fw.prepared
        if (prepared != null) {
            ListItem(
                headlineContent = { Text(prepared.title, style = MaterialTheme.typography.titleMedium) },
                supportingContent = {
                    Column {
                        Text(preparedDetail(context, prepared), style = BrandText.timecode.copy(fontSize = 12.sp))
                        if (!prepared.isSigned) {
                            Text("No signature. A normal device refuses unsigned firmware; developer builds accept it.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                colors = transparentItem(),
            )
            model.installBlocker?.let { blocker ->
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Lock, null) },
                    headlineContent = { Text(blocker, style = MaterialTheme.typography.bodySmall) },
                    colors = transparentItem(),
                )
            }
            ListItem(
                modifier = Modifier.clickable(enabled = model.installBlocker == null, onClick = onInstall),
                headlineContent = {
                    Text("Install…", color = if (model.installBlocker == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                },
                colors = transparentItem(),
            )
            if (!prepared.isSigned && prepared.fileName != null) {
                ListItem(modifier = Modifier.clickable(onClick = onChooseFile), headlineContent = { Text("Add signature file…") }, colors = transparentItem())
            }
            ListItem(
                modifier = Modifier.clickable { model.discardPrepared() },
                headlineContent = { Text("Remove") },
                colors = transparentItem(),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        } else if (fw.offer != null) {
            ListItem(
                modifier = Modifier.clickable { model.downloadOffer() },
                headlineContent = { Text("Download") },
                colors = transparentItem(),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
        val checking = fw.stage == FirmwareStage.Checking
        ListItem(
            modifier = Modifier.clickable(enabled = !checking) { model.checkForUpdates() },
            headlineContent = { Text("Check for updates") },
            trailingContent = { if (checking) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp) },
            colors = transparentItem(),
        )
    }
}

@Composable
private fun ProgressSection(title: String, value: Double?, cancellable: Boolean, onCancel: () -> Unit) {
    Section(title = null) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (value != null) {
                LinearProgressIndicator(progress = { value.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("${(value * 100).toInt()}%", style = BrandText.timecode.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                "Keep this screen open. The device restarts when it's done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cancellable) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(modifier = Modifier.clickable(onClick = onCancel), headlineContent = { Text("Cancel") }, colors = transparentItem())
        }
    }
}

@Composable
private fun ResultSection(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onDone: () -> Unit) {
    Section(title = null) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        ListItem(modifier = Modifier.clickable(onClick = onDone), headlineContent = { Text("Done") }, colors = transparentItem())
    }
}

/** Older or other releases, for the Advanced section. */
@Composable
private fun VersionDialog(model: AppModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a version") },
        text = {
            val releases = model.firmware.releases
            when {
                model.firmware.loadingReleases -> CircularProgressIndicator()
                releases == null -> Text(model.firmware.message ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                releases.isEmpty() -> Text("No releases found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn {
                    items(releases, key = { it.tag }) { release ->
                        ListItem(
                            modifier = Modifier.clickable {
                                onDismiss()
                                model.chooseRelease(release)
                            },
                            headlineContent = { Text(release.version, fontFamily = MonoFamily) },
                            trailingContent = { if (release.prerelease) StatePill("Pre-release") },
                            colors = transparentItem(),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun preparedDetail(context: android.content.Context, p: PreparedFirmware): String =
    listOfNotNull(
        p.fileName,
        Formatter.formatShortFileSize(context, p.data.size.toLong()),
        if (p.isSigned) "signed" else "unsigned",
        p.sha256.take(12),
    ).joinToString(" · ")

private fun installMessage(model: AppModel, prepared: PreparedFirmware?): String {
    var text = "The device will restart. Keep the app open until it finishes."
    val version = prepared?.version
    val fw = model.info?.fw
    if (version != null && fw != null && FirmwareVersion.compare(version, fw) == -1) {
        text = "This is older than the version on the device. $text"
    }
    if (prepared?.fileName != null && version != null && !prepared.title.startsWith("Version")) {
        text = "This file didn't come from a Kairos release download. $text"
    }
    return text
}
