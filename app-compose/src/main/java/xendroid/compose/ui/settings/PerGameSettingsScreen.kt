package xendroid.compose.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import xendroid.compose.settings.GameSettingsViewModel
import xendroid.compose.settings.SettingsCategory
import xendroid.compose.ui.compress.GameCompressViewModel
import xendroid.compose.ui.compress.GameCompressViewModel.CompressState

/**
 * The per-game override editor: the same two-level INDEX -> DETAIL shape as
 * [SettingsScreen], but each detail row is an [OverrideRow] (a leading switch that
 * overrides/inherits the key), the index "changed" count is the overridden count, and
 * the header shows the game name. The override config is SPARSE — only toggled-on keys
 * are written, and the file is rebuilt/deleted on flush (no native key-erase exists).
 * A title id is keyed PER GAME (not per file), so this applies to every copy of the game.
 */
@Composable
fun PerGameSettingsScreen(
    vm: GameSettingsViewModel,
    gameName: String,
    isIso: Boolean,
    launchUri: String?,
    compressVm: GameCompressViewModel,
    onBack: () -> Unit,
) {
    val overrides by vm.overrides.collectAsStateWithLifecycle()

    // Durable flush on pause; re-open on resume. Dispose flush = backstop. (Mirrors SettingsScreen.)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> vm.flush()
                Lifecycle.Event.ON_RESUME -> vm.onResume()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); vm.flush() }
    }

    // Compress-to-.zar dialogs live at the top level so they survive the index/detail toggle.
    var showCompressConfirm by remember { mutableStateOf(false) }
    val compressState by compressVm.state.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf<SettingsCategory?>(null) }
    val section = selected
    if (section == null) {
        PerGameIndex(
            gameName = gameName,
            categories = vm.categories,
            overriddenCountOf = { cat -> cat.settings.count { overrides[it.key] == true } },
            onOpen = { selected = it },
            isIso = isIso,
            launchUri = launchUri,
            onCompress = { showCompressConfirm = true },
            onBack = { vm.flush(); onBack() },
        )
    } else {
        BackHandler { selected = null }
        PerGameCategoryDetail(
            category = section,
            overrides = overrides,
            vm = vm,
            onBack = { selected = null },
        )
    }

    if (showCompressConfirm) {
        AlertDialog(
            onDismissRequest = { showCompressConfirm = false },
            title = { Text("Compress to .zar?") },
            text = {
                Text(
                    "This packs the disc into a smaller .zar. The original .iso will be " +
                        "deleted only after the .zar is created and verified. The game stays " +
                        "in your library.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showCompressConfirm = false
                    launchUri?.let(compressVm::compress)
                }) { Text("Compress") }
            },
            dismissButton = {
                TextButton(onClick = { showCompressConfirm = false }) { Text("Cancel") }
            },
        )
    }

    when (val s = compressState) {
        is CompressState.Busy -> AlertDialog(
            onDismissRequest = {},   // not cancelable while running
            title = { Text(s.message) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${(s.progress * 100).toInt()}%  ·  this may take a while.")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("This may take a while.")
                    }
                }
            },
            confirmButton = {},
        )
        is CompressState.Done -> AlertDialog(
            onDismissRequest = compressVm::dismiss,
            title = { Text("Done") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = compressVm::dismiss) { Text("OK") } },
        )
        is CompressState.Failed -> AlertDialog(
            onDismissRequest = compressVm::dismiss,
            title = { Text("Failed") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = compressVm::dismiss) { Text("OK") } },
        )
        else -> {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PerGameIndex(
    gameName: String,
    categories: List<SettingsCategory>,
    overriddenCountOf: (SettingsCategory) -> Int,
    onOpen: (SettingsCategory) -> Unit,
    isIso: Boolean,
    launchUri: String?,
    onCompress: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (gameName.isNotEmpty()) "$gameName settings" else "Per-game settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            // Header note: title id is keyed per game, so overrides apply to every copy.
            item {
                Text(
                    "Overrides apply to all copies of this game.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
            }
            items(categories, key = { it.title }) { cat ->
                val overridden = overriddenCountOf(cat)
                ListItem(
                    headlineContent = { Text(cat.title) },
                    supportingContent = {
                        Text(buildString {
                            append("${cat.settings.size} settings")
                            if (overridden > 0) append("  ·  $overridden overridden")
                        })
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open")
                    },
                    modifier = Modifier.clickable { onOpen(cat) },
                )
                HorizontalDivider()
            }
            // ISO-only: pack this disc into a smaller .zar and replace the .iso.
            if (isIso && launchUri != null) {
                item {
                    ListItem(
                        headlineContent = { Text("Compress to .zar") },
                        supportingContent = {
                            Text("Pack this disc into a smaller .zar and replace the .iso.")
                        },
                        modifier = Modifier.clickable { onCompress() },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PerGameCategoryDetail(
    category: SettingsCategory,
    overrides: Map<String, Boolean>,
    vm: GameSettingsViewModel,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(category.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sections")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(category.settings, key = { it.key }) { setting ->
                OverrideRow(
                    host = vm,
                    s = setting,
                    overridden = overrides[setting.key] == true,
                    onOverrideToggle = { vm.setOverride(setting, it) },
                )
                HorizontalDivider()
            }
        }
    }
}
