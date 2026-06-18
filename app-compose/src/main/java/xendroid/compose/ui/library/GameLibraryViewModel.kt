package xendroid.compose.ui.library

import xendroid.compose.core.R
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xendroid.compose.core.EmulatorRuntime
import xendroid.compose.data.Game
import xendroid.compose.data.GameFormat
import xendroid.compose.data.GameLibraryRepository
import xendroid.compose.data.IconCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The launch action emitted on game tap. The host Activity that resolves it
 *  arrives in SP1-C; until then this Intent will not resolve (no-op resolveActivity). */
const val ACTION_LAUNCH_GAME = "xendroid.intent.action.xendroid"
const val EXTRA_GAME_URI = "game_uri"

/** Async resolution of a game's title id (needed before the per-game settings editor
 *  can open). Driven by the long-press dialog; only GOD resolves (see [GameLibraryRepository.readTitleId]). */
sealed interface TitleIdState {
    data object Idle : TitleIdState
    data class Loading(val game: Game) : TitleIdState
    data class Resolved(val game: Game, val titleId: String) : TitleIdState
    data class Error(val game: Game, val message: String) : TitleIdState
}

class GameLibraryViewModel(
    private val repo: GameLibraryRepository,
    private val iconCache: IconCache,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val _titleId = MutableStateFlow<TitleIdState>(TitleIdState.Idle)
    val titleIdState: StateFlow<TitleIdState> = _titleId.asStateFlow()

    /** True while a scan is running. Drives the swipe-down (pull-to-refresh) indicator
     *  WITHOUT flashing the full-screen spinner over an already-loaded list. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (!EmulatorRuntime.supportsVulkan) { _state.value = LibraryUiState.NoVulkan; return }
        // Keep an existing list visible during a pull-to-refresh (show only the pull
        // indicator); the full-screen spinner is for the first/empty load.
        if (_state.value !is LibraryUiState.Loaded) _state.value = LibraryUiState.Loading
        _isRefreshing.value = true
        viewModelScope.launch {
            _state.value = runCatching {
                // ensureLoaded() can sleep + System.loadLibrary on delay-load devices
                // (Adreno 5xx/6xx) -> never on the main thread.
                withContext(Dispatchers.IO) { EmulatorRuntime.ensureLoaded() }
                when (val r = repo.scan()) {
                    GameLibraryRepository.ScanResult.NoFolder -> LibraryUiState.NoFolder
                    GameLibraryRepository.ScanResult.PermissionLost -> LibraryUiState.PermissionLost
                    is GameLibraryRepository.ScanResult.Games -> LibraryUiState.Loaded(r.games)
                }
            }.getOrElse { LibraryUiState.Error(it.message ?: "Failed to load library") }
            _isRefreshing.value = false
        }
    }

    fun onDirectoryPicked(treeUri: Uri) {
        viewModelScope.launch {
            repo.saveGameDir(treeUri)
            refresh()
        }
    }

    /** Resolve a game's title id off-main so the long-press dialog can open the per-game
     *  editor. GOD/ISO/XEX_FOLDER have boot-free readers; ZAR short-circuits to an Error. */
    fun requestPerGameSettings(game: Game) {
        _titleId.value = TitleIdState.Loading(game)
        viewModelScope.launch(Dispatchers.IO) {
            _titleId.value = runCatching {
                EmulatorRuntime.ensureLoaded()
                if (game.format == GameFormat.ZAR) {
                    return@runCatching TitleIdState.Error(
                        game, "Per-game settings aren't available for ZAR games yet"
                    )
                }
                val tid = repo.readTitleId(appContext, game)
                // 00000000 is the unknown/placeholder title id (no real GOD carries it).
                if (tid.isNullOrBlank() || tid == "00000000")
                    TitleIdState.Error(game, "Couldn't read this game's title id")
                else TitleIdState.Resolved(game, tid)
            }.getOrElse { TitleIdState.Error(game, it.message ?: "Failed to read title id") }
        }
    }

    fun clearTitleIdRequest() { _titleId.value = TitleIdState.Idle }

    /** Build the launch Intent (host pending in SP1-C). Caller startActivity()s it. */
    fun buildLaunchIntent(game: Game): Intent =
        Intent(ACTION_LAUNCH_GAME).apply {
            setPackage(appContext.packageName)          // self; host is in this app
            putExtra(EXTRA_GAME_URI, game.launchUri)
        }

    /** Coil model for a game's icon: the cached PNG File when present, else the
     *  app_icon drawable resource id. Kept here so the View carries no IconCache dep. */
    fun iconFileOrFallback(game: Game): Any {
        val file = game.iconCacheName?.let { iconCache.fileFor(it) }
        return if (file != null && file.exists()) file
        else R.drawable.app_icon
    }

    val isPinShortcutSupported: Boolean
        get() = appContext.getSystemService<ShortcutManager>()
            ?.isRequestPinShortcutSupported == true

    /** True once a host Activity resolves the launch action (arrives in SP1-C).
     *  Until then, suppress launch-dependent affordances so we never pin a dead shortcut. */
    val canLaunchGames: Boolean
        get() = Intent(ACTION_LAUNCH_GAME)
            .setPackage(appContext.packageName)
            .resolveActivity(appContext.packageManager) != null

    /** Pin a launcher shortcut. Stable id = game.stableId (uri) to avoid name
     *  collisions (legacy used the display name -> collisions). */
    fun createShortcut(game: Game) {
        if (!isPinShortcutSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sm = appContext.getSystemService<ShortcutManager>() ?: return
        val intent = buildLaunchIntent(game).apply { action = Intent.ACTION_VIEW }
        // SP1-C provides the resolving host; don't pin a shortcut that goes nowhere.
        if (intent.resolveActivity(appContext.packageManager) == null) return
        val icon = game.iconCacheName
            ?.let { iconCache.fileFor(it) }
            ?.takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
            ?.let { Icon.createWithBitmap(it) }
            ?: Icon.createWithResource(appContext, R.drawable.app_icon)
        sm.requestPinShortcut(
            ShortcutInfo.Builder(appContext, game.stableId)
                .setShortLabel(game.name)
                .setIcon(icon)
                .setIntent(intent)
                .build(),
            null
        )
    }
}
