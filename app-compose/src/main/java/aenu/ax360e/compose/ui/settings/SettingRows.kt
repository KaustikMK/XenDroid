package aenu.ax360e.compose.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import aenu.ax360e.compose.settings.*

@Composable
fun SettingRow(vm: SettingsViewModel, s: Setting, modified: Boolean) = when (s) {
    is Setting.Bool       -> BoolRow(vm, s, modified)
    is Setting.IntRange   -> IntRow(vm, s, modified)
    is Setting.ListChoice -> ListRow(vm, s, modified)
    is Setting.Action     -> DriverActionRow(vm, s, modified)
}

@Composable
private fun titleColor(modified: Boolean) =
    if (modified) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface

@Composable
private fun RowTitle(text: String, modified: Boolean, sub: String? = null) {
    Column {
        Text(text, color = titleColor(modified), style = MaterialTheme.typography.bodyLarge)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BoolRow(vm: SettingsViewModel, s: Setting.Bool, modified: Boolean) {
    val checked = remember(modified) { vm.currentBool(s) }
    var local by remember { mutableStateOf(checked) }
    Row(
        Modifier.fillMaxWidth().clickable { local = !local; vm.onBoolChanged(s, local) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { RowTitle(s.title, modified) }
        Switch(checked = local, onCheckedChange = { local = it; vm.onBoolChanged(s, it) })
    }
}

@Composable
private fun IntRow(vm: SettingsViewModel, s: Setting.IntRange, modified: Boolean) {
    var showDialog by remember { mutableStateOf(false) }
    val current = remember(modified) { vm.currentInt(s) }
    Row(Modifier.fillMaxWidth().clickable { showDialog = true }
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { RowTitle(s.title, modified, sub = current.toString()) }
    }
    if (showDialog) {
        var slider by remember { mutableFloatStateOf(current.coerceIn(s.min, s.max).toFloat()) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(s.title) },
            text = {
                Column {
                    Text(slider.toInt().toString(), style = MaterialTheme.typography.titleLarge)
                    Slider(value = slider, onValueChange = { slider = it },
                        valueRange = s.min.toFloat()..s.max.toFloat(),
                        steps = (s.max - s.min - 1).coerceAtLeast(0))
                }
            },
            confirmButton = { TextButton(onClick = {
                vm.onIntChanged(s, slider.toInt()); showDialog = false
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ListRow(vm: SettingsViewModel, s: Setting.ListChoice, modified: Boolean) {
    var showDialog by remember { mutableStateOf(false) }
    val currentValue = remember(modified) { vm.currentListValue(s) }
    val currentLabel = s.options.firstOrNull { it.value == currentValue }?.label
        ?: if (currentValue.isEmpty()) "(default)" else currentValue
    Row(Modifier.fillMaxWidth().clickable { showDialog = true }
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { RowTitle(s.title, modified, sub = currentLabel) }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(s.title) },
            text = {
                Column {
                    s.options.forEach { opt ->
                        Row(Modifier.fillMaxWidth().selectable(
                            selected = opt.value == currentValue,
                            onClick = { vm.onListChanged(s, opt.value); showDialog = false },
                        ).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = opt.value == currentValue, onClick = {
                                vm.onListChanged(s, opt.value); showDialog = false })
                            Spacer(Modifier.width(8.dp)); Text(opt.label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DriverActionRow(vm: SettingsViewModel, s: Setting.Action, modified: Boolean) {
    if (!vm.isCustomDriverSupported) return  // gated: not an Adreno/kgsl device
    val context = LocalContext.current
    val current = remember(modified) { vm.currentDriverPath(s) }
    // .zip picker -> install via Utils on the host Activity (see note below).
    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val activity = context as? android.app.Activity ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            aenu.ax360e.Utils.install_custom_driver_from_zip(activity, uri) { path ->
                vm.onDriverPathChanged(s, path)   // installed path
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { pickZip.launch(arrayOf("application/zip")) }
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                RowTitle(s.title, modified,
                    sub = current.ifEmpty { "_default" })
            }
        }
        // "" clears vulkan_lib_path -> native falls back to the system driver. (Writing a
        // literal "default" would make native try to dlopen a driver named "default".)
        TextButton(onClick = { vm.onDriverPathChanged(s, "") },
            modifier = Modifier.padding(start = 8.dp)) { Text("Use default driver") }
    }
}
