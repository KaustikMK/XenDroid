package aenu.ax360e.compose.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
// NOTE: the plan imported Icons.Default.Folder, but Folder ships only in
// material-icons-extended (not on the configured classpath; only material-icons-core
// is pulled transitively by material3). Using Add (available in core) for the
// "set game folder" action to keep the dependency set the plan specified. Refresh is
// in core. See the implementation report for this deviation.
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import aenu.ax360e.compose.data.Game

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLibraryScreen(
    viewModel: GameLibraryViewModel,
    onOpenSettings: () -> Unit,
    onOpenKeymap: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // OPEN_DOCUMENT_TREE picker; on result persist + rescan.
    val pickDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::onDirectoryPicked) }

    var pendingShortcut by remember { mutableStateOf<Game?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { pickDir.launch(null) }) {
                        Icon(Icons.Default.Add, contentDescription = "Set game folder")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Key mapping") },
                            onClick = { menuOpen = false; onOpenKeymap() },
                        )
                        DropdownMenuItem(
                            text = { Text("Open user data") },
                            onClick = {
                                menuOpen = false
                                aenu.ax360e.compose.ui.userdata.openUserData(context)
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
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val s = state) {
                LibraryUiState.NoVulkan ->
                    NoVulkanDialog(onQuit = { (context as? android.app.Activity)?.finish() })
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
                        // Suppress the shortcut affordance until a launchable host exists (SP1-C).
                        onLongPress = { if (viewModel.canLaunchGames) pendingShortcut = it },
                    )
            }
        }
    }

    pendingShortcut?.let { game ->
        if (viewModel.isPinShortcutSupported) {
            AlertDialog(
                onDismissRequest = { pendingShortcut = null },
                confirmButton = {
                    TextButton(onClick = { viewModel.createShortcut(game); pendingShortcut = null }) {
                        Text("Create shortcut")
                    }
                },
                dismissButton = { TextButton(onClick = { pendingShortcut = null }) { Text("Cancel") } },
                title = { Text(game.name) },
            )
        } else pendingShortcut = null
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
