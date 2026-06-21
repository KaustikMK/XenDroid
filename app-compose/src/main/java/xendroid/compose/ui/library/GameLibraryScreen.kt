package xendroid.compose.ui.library

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import xendroid.compose.data.Game
import xendroid.compose.data.GameFormat
import xendroid.compose.ui.compress.GameCompressViewModel
import xendroid.compose.ui.compress.GameCompressViewModel.CompressState
import xendroid.compose.ui.userdata.openUserData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLibraryScreen(
    viewModel: GameLibraryViewModel,
    onOpenSettings: () -> Unit,
    onOpenKeymap: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenTouchControls: () -> Unit,
    onOpenPerGameSettings: (titleId: String, gameName: String, format: GameFormat, launchUri: String) -> Unit,
    compressVm: GameCompressViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // OPEN_DOCUMENT_TREE picker; on result persist + rescan.
    val pickDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::onDirectoryPicked) }

    // The long-press menu target (per-game settings, optionally shortcut).
    var pendingGame by remember { mutableStateOf<Game?>(null) }
    // Long-press "Compress to .zar" (ISO only): the game awaiting the confirm dialog.
    var compressConfirmFor by remember { mutableStateOf<Game?>(null) }
    val compressState by compressVm.state.collectAsStateWithLifecycle()
    val titleIdState by viewModel.titleIdState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    // Re-scan when the app returns to the foreground (picks up games added while it was
    // backgrounded). The ViewModel's init does the first cold-start load, so the first
    // ON_START is skipped to avoid doubling it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var firstStart = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (firstStart) firstStart = false else viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Set game folder") },
                            onClick = { menuOpen = false; pickDir.launch(null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Key mapping") },
                            onClick = { menuOpen = false; onOpenKeymap() },
                        )
                        DropdownMenuItem(
                            text = { Text("Touch controls") },
                            onClick = { menuOpen = false; onOpenTouchControls() },
                        )
                        DropdownMenuItem(
                            text = { Text("Open user data") },
                            onClick = {
                                menuOpen = false
                                openUserData(context)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = { menuOpen = false; onOpenAbout() },
                        )
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val s = state) {
                LibraryUiState.NoVulkan ->
                    NoVulkanDialog(onQuit = { (context as? Activity)?.finish() })
                LibraryUiState.Loading -> CircularProgressIndicator()
                LibraryUiState.NoFolder ->
                    EmptyMessage("No game folder set", "Pick folder", onAction = { pickDir.launch(null) })
                LibraryUiState.PermissionLost ->
                    EmptyMessage("Folder access lost", "Re-pick folder", onAction = { pickDir.launch(null) })
                is LibraryUiState.Error ->
                    EmptyMessage(s.message, "Retry", onAction = { viewModel.refresh() })
                is LibraryUiState.Loaded ->
                    if (s.games.isEmpty())
                        EmptyMessage("No games in this folder", "Pick another", onAction = { pickDir.launch(null) })
                    else GameGrid(
                        games = s.games,
                        viewModel = viewModel,
                        onLaunch = { game ->
                            runCatching { context.startActivity(viewModel.buildLaunchIntent(game)) }
                            // No-op until a host Activity resolves the launch action.
                        },
                        // Long-press opens the per-game menu (independent of canLaunchGames,
                        // which only gates the shortcut affordance inside the dialog).
                        onLongPress = { pendingGame = it },
                    )
            }
        }
        }
    }

    pendingGame?.let { game ->
        val dismiss = { pendingGame = null; viewModel.clearTitleIdRequest() }
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = dismiss,
            sheetState = sheetState,
        ) {
            Column {
                // Sheet header: prominent game name; the title-id status line shows ONLY
                // while resolving or on error (no static subtitle otherwise).
                val statusContent: (@Composable () -> Unit)? = when (val st = titleIdState) {
                    is TitleIdState.Loading -> ({
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Reading title id…")
                        }
                    })
                    is TitleIdState.Error -> ({ Text(st.message) })
                    else -> null
                }
                ListItem(
                    headlineContent = {
                        Text(game.name, style = MaterialTheme.typography.titleLarge)
                    },
                    supportingContent = statusContent,
                )

                // Per-game settings — disabled (and visually dimmed) while the title id resolves.
                val perGameEnabled = titleIdState !is TitleIdState.Loading
                ListItem(
                    headlineContent = { Text("Per-game settings") },
                    colors = if (perGameEnabled) {
                        ListItemDefaults.colors()
                    } else {
                        ListItemDefaults.colors(
                            headlineColor =
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    modifier = Modifier.clickable(enabled = perGameEnabled) {
                        viewModel.requestPerGameSettings(game)
                    },
                )

                // ISO-only: pack this disc into a smaller .zar.
                if (game.format == GameFormat.ISO) {
                    ListItem(
                        headlineContent = { Text("Compress to .zar") },
                        modifier = Modifier.clickable {
                            compressConfirmFor = game
                            pendingGame = null
                            viewModel.clearTitleIdRequest()
                        },
                    )
                }

                // Shortcut affordance when a launchable host exists.
                if (viewModel.canLaunchGames && viewModel.isPinShortcutSupported) {
                    ListItem(
                        headlineContent = { Text("Create shortcut") },
                        modifier = Modifier.clickable {
                            viewModel.createShortcut(game)
                            dismiss()
                        },
                    )
                }
            }
        }
    }

    // Once resolved, navigate to the per-game editor and reset dialog + request state.
    LaunchedEffect(titleIdState) {
        (titleIdState as? TitleIdState.Resolved)?.let { r ->
            onOpenPerGameSettings(r.titleId, r.game.name, r.game.format, r.game.launchUri)
            pendingGame = null
            viewModel.clearTitleIdRequest()
        }
    }

    // ISO -> .zar compress, launched straight from the long-press popup. Confirm, then
    // GameCompressViewModel runs the safe compress+verify+replace; refresh on Done so the
    // .iso entry turns into the new .zar in the grid.
    compressConfirmFor?.let { game ->
        AlertDialog(
            onDismissRequest = { compressConfirmFor = null },
            title = { Text("Compress to .zar?") },
            text = {
                Text(
                    "This packs the disc into a smaller .zar. The original .iso is deleted " +
                        "only after the .zar is created and verified. The game stays in your library.")
            },
            confirmButton = {
                TextButton(onClick = {
                    compressConfirmFor = null
                    compressVm.compress(game.launchUri)
                }) { Text("Compress") }
            },
            dismissButton = {
                TextButton(onClick = { compressConfirmFor = null }) { Text("Cancel") }
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
            onDismissRequest = { compressVm.dismiss(); viewModel.refresh() },
            title = { Text("Done") },
            text = { Text(s.message) },
            confirmButton = {
                TextButton(onClick = { compressVm.dismiss(); viewModel.refresh() }) { Text("OK") }
            },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameGrid(
    games: List<Game>,
    viewModel: GameLibraryViewModel,
    onLaunch: (Game) -> Unit,
    onLongPress: (Game) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(games, key = { it.stableId }) { game ->
            GameCell(game, viewModel, onLaunch, onLongPress)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCell(
    game: Game,
    viewModel: GameLibraryViewModel,
    onLaunch: (Game) -> Unit,
    onLongPress: (Game) -> Unit,
) {
    val context = LocalContext.current
    // Resolve the icon model once per cell (the File.exists() stat must not run on
    // every recomposition while scrolling).
    val iconModel = remember(game.stableId) { viewModel.iconFileOrFallback(game) }
    Column(
        Modifier
            .padding(8.dp)
            .combinedClickable(onClick = { onLaunch(game) }, onLongClick = { onLongPress(game) }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(iconModel)   // File or app_icon res id
                .build(),
            contentDescription = game.name,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            game.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyMessage(text: String, action: String, onAction: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun NoVulkanDialog(onQuit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onQuit,
        confirmButton = { TextButton(onClick = onQuit) { Text("Quit") } },
        title = { Text("Unsupported device") },
        text = { Text("This device has no Vulkan GPU; the emulator cannot run.") },
    )
}
