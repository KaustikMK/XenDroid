package xendroid.compose.ui.library

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import xendroid.compose.ui.userdata.openUserData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLibraryScreen(
    viewModel: GameLibraryViewModel,
    onOpenSettings: () -> Unit,
    onOpenKeymap: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenTouchControls: () -> Unit,
    onOpenPerGameSettings: (titleId: String, gameName: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // OPEN_DOCUMENT_TREE picker; on result persist + rescan.
    val pickDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::onDirectoryPicked) }

    // The long-press menu target (per-game settings, optionally shortcut).
    var pendingGame by remember { mutableStateOf<Game?>(null) }
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
                            // SP1-C provides the resolving host; until then this is a no-op.
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
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(game.name) },
            text = {
                when (val st = titleIdState) {
                    is TitleIdState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Reading title id…")
                    }
                    is TitleIdState.Error -> Text(st.message)
                    else -> Text("Configure settings that apply only to this game.")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = titleIdState !is TitleIdState.Loading,
                    onClick = { viewModel.requestPerGameSettings(game) },
                ) { Text("Per-game settings") }
            },
            dismissButton = {
                Row {
                    // Keep the shortcut affordance when a launchable host exists (SP1-C).
                    if (viewModel.canLaunchGames && viewModel.isPinShortcutSupported) {
                        TextButton(onClick = { viewModel.createShortcut(game); dismiss() }) {
                            Text("Create shortcut")
                        }
                    }
                    TextButton(onClick = dismiss) { Text("Cancel") }
                }
            },
        )
    }

    // Once resolved, navigate to the per-game editor and reset dialog + request state.
    LaunchedEffect(titleIdState) {
        (titleIdState as? TitleIdState.Resolved)?.let { r ->
            onOpenPerGameSettings(r.titleId, r.game.name)
            pendingGame = null
            viewModel.clearTitleIdRequest()
        }
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
