@file:OptIn(ExperimentalMaterial3Api::class)

package com.noahprail.kairos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noahprail.kairos.AppModel
import com.noahprail.kairos.protocol.CameraConfig
import com.noahprail.kairos.protocol.StatusValue
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Composable
private fun transparent() = ListItemDefaults.colors(containerColor = Color.Transparent)

private fun cameraTypeLabel(type: String) = when (type) {
    "generic" -> "Remote trigger (GPIO)"
    "zcam" -> "Z CAM (network)"
    else -> type
}

@Composable
fun ConfigEditorScreen(model: AppModel, onBack: () -> Unit) {
    // edits go to a draft; nothing reaches the device until Save
    var draft by remember { mutableStateOf(model.config) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val busy = (model.live?.phase ?: StatusValue.Phase.IDLE) != StatusValue.Phase.IDLE
    val current = draft
    val original = model.config
    val dirty = current != null && original != null && !current.sameAs(original)

    fun save() {
        val toSave = draft ?: return
        saving = true
        scope.launch {
            val error = model.saveConfig(toSave)
            saving = false
            if (error != null) errorText = error else onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedule & Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(onClick = ::save, enabled = dirty && !saving && !busy) { Text("Save") }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (current == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (busy) BusyNotice()
                AttemptsSection(current, enabled = !saving) { draft = it }
                TimingSection(current, enabled = !saving) { draft = it }
                CameraSection(current, enabled = !saving) { draft = it }
                GeneralSection(current, enabled = !saving) { draft = it }
                androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp))
            }
        }
    }

    errorText?.let { text ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text("Couldn't save") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { errorText = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun BusyNotice() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Lock, null)
            Text("The device is busy. Changes can be saved when it's idle.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// region Attempts

@Composable
private fun AttemptsSection(config: CameraConfig, enabled: Boolean, onChange: (CameraConfig) -> Unit) {
    Section(
        title = "Launch attempts",
        footer = "Times are in your local time zone (${localTimeZoneAbbreviation()}). The device works in UTC. Up to 30 attempts.",
    ) {
        config.attempts.forEachIndexed { index, attempt ->
            AttemptRow(
                attempt = attempt,
                enabled = enabled,
                onChange = { updated ->
                    onChange(config.copy(attempts = config.attempts.toMutableList().also { it[index] = updated }))
                },
                onDelete = {
                    onChange(config.copy(attempts = config.attempts.toMutableList().also { it.removeAt(index) }))
                },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
        ListItem(
            modifier = Modifier.clickable(enabled = enabled) {
                onChange(config.copy(attempts = config.attempts + newAttempt(config)))
            },
            leadingContent = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text("Add attempt", color = MaterialTheme.colorScheme.primary) },
            colors = transparent(),
        )
    }
}

/** Default for a new attempt: the next full hour after now, or after the latest existing attempt. */
private fun newAttempt(config: CameraConfig): CameraConfig.Attempt {
    val base = maxOf(Instant.now(), config.attempts.maxOfOrNull { it.instant } ?: Instant.now())
    val hour = ZonedDateTime.ofInstant(base, ZoneId.systemDefault()).withMinute(0).withSecond(0).withNano(0).plusHours(1)
    return CameraConfig.Attempt.create("Attempt ${config.attempts.size + 1}", hour.toInstant())
}

@Composable
private fun AttemptRow(
    attempt: CameraConfig.Attempt,
    enabled: Boolean,
    onChange: (CameraConfig.Attempt) -> Unit,
    onDelete: () -> Unit,
) {
    var picking by remember { mutableStateOf<String?>(null) }
    val local = ZonedDateTime.ofInstant(attempt.instant, ZoneId.systemDefault())

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = attempt.name,
                onValueChange = { onChange(attempt.copy(name = it)) },
                label = { Text("Name") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove ${attempt.name}")
            }
        }
        Text("Launch (T-0)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { picking = "date" },
                enabled = enabled,
                label = { Text(attempt.instant.formatDate()) },
                leadingIcon = { Icon(Icons.Filled.CalendarMonth, null, Modifier.size(18.dp)) },
            )
            AssistChip(
                onClick = { picking = "time" },
                enabled = enabled,
                label = { Text(attempt.instant.formatTime()) },
                leadingIcon = { Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp)) },
            )
        }
    }

    when (picking) {
        "date" -> {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            onChange(attempt.withInstant(ZonedDateTime.of(date, local.toLocalTime(), ZoneId.systemDefault()).toInstant()))
                        }
                        picking = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
            ) { DatePicker(state) }
        }
        "time" -> {
            val state = rememberTimePickerState(
                initialHour = local.hour,
                initialMinute = local.minute,
                is24Hour = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current),
            )
            AlertDialog(
                onDismissRequest = { picking = null },
                title = { Text("Launch time") },
                text = { TimePicker(state) },
                confirmButton = {
                    TextButton(onClick = {
                        val time = LocalTime.of(state.hour, state.minute)
                        onChange(attempt.withInstant(ZonedDateTime.of(local.toLocalDate(), time, ZoneId.systemDefault()).toInstant()))
                        picking = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
            )
        }
    }
}

// endregion

// region Timing

@Composable
private fun TimingSection(config: CameraConfig, enabled: Boolean, onChange: (CameraConfig) -> Unit) {
    Section(title = "Timing") {
        Stepper("Wake camera", "${config.wakeUpTimeout.toInt()} s before recording", config.wakeUpTimeout, enabled) {
            onChange(config.copy(wakeUpTimeout = it))
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Stepper("Start recording", "${config.startBefore.toInt()} s before T-0", config.startBefore, enabled) {
            onChange(config.copy(startBefore = it))
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Stepper("Stop recording", "${config.endAfter.toInt()} s after T-0", config.endAfter, enabled) {
            onChange(config.copy(endAfter = it))
        }
    }
}

/** A value from 0 to 600 s, in steps of 1. */
@Composable
private fun Stepper(label: String, description: String, value: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(description) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { onChange((value - 1).coerceAtLeast(0.0)) }, enabled = enabled && value > 0) {
                    Icon(Icons.Filled.Remove, contentDescription = "Decrease $label")
                }
                FilledTonalIconButton(onClick = { onChange((value + 1).coerceAtMost(600.0)) }, enabled = enabled && value < 600) {
                    Icon(Icons.Filled.Add, contentDescription = "Increase $label")
                }
            }
        },
        colors = transparent(),
    )
}

// endregion

// region Camera and device

@Composable
private fun CameraSection(config: CameraConfig, enabled: Boolean, onChange: (CameraConfig) -> Unit) {
    Section(title = "Camera") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && enabled }) {
                OutlinedTextField(
                    value = cameraTypeLabel(config.cameraType),
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text("Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    CameraConfig.cameraTypes.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(cameraTypeLabel(type)) },
                            onClick = {
                                onChange(config.copy(cameraType = type))
                                expanded = false
                            },
                        )
                    }
                }
            }

            if (config.cameraType == "generic") {
                IntField("Release pin", "GPIO", config.generic.releaseGpioPin, enabled) {
                    onChange(config.copy(generic = config.generic.copy(releaseGpioPin = it)))
                }
                IntField("Focus pin", "Optional", config.generic.focusGpioPin, enabled) {
                    onChange(config.copy(generic = config.generic.copy(focusGpioPin = it)))
                }
            } else {
                OutlinedTextField(
                    value = config.zcam.cameraIp ?: "",
                    onValueChange = { onChange(config.copy(zcam = config.zcam.copy(cameraIp = it.ifEmpty { null }))) },
                    label = { Text("Camera IP") },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun GeneralSection(config: CameraConfig, enabled: Boolean, onChange: (CameraConfig) -> Unit) {
    Section(title = "Device") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = config.name ?: "",
                onValueChange = { onChange(config.copy(name = it.ifEmpty { null })) },
                label = { Text("Name") },
                placeholder = { Text("Optional") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            IntField("Button pin", "Optional", config.buttonGpioPort, enabled) { onChange(config.copy(buttonGpioPort = it)) }
        }
    }
}

/** An optional integer field: empty or non-numeric text becomes null. */
@Composable
private fun IntField(label: String, placeholder: String, value: Int?, enabled: Boolean, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { onChange(it.trim().toIntOrNull()) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

// endregion
