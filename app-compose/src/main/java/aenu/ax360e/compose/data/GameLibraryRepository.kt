package aenu.ax360e.compose.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import aenu.ax360e.compose.core.EmulatorRuntime
import aenu.ax360e.compose.core.GameMetadataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * Discovers games by walking ONE level of the persisted SAF tree (no recursion),
 * mirroring legacy GameMetaInfoAdapter.refresh_game_list. GOD metadata + icons come
 * from native; ISO/ZAR/XEX get filename-derived names and the app_icon fallback.
 */
class GameLibraryRepository(
    private val appContext: Context,
    private val prefs: PreferencesStore,
    private val metadata: GameMetadataSource,
    private val iconCache: IconCache,
) {
    private val tag = "GameLibraryRepo"

    /** Set once we've handed the native side this (main/library) process's context + SAF tree,
     *  which the ISO title-id disc-mount needs (g_context + the tree); only :emu set them before. */
    @Volatile private var nativeSafReady = false

    /** Result of a scan: distinguishes "no folder" / "grant lost" from a real list. */
    sealed interface ScanResult {
        data object NoFolder : ScanResult
        data object PermissionLost : ScanResult
        data class Games(val games: List<Game>) : ScanResult
    }

    /** (Re)take the persistable read grant, then persist the tree uri ONLY if the
     *  grant succeeded (otherwise a later scan would fail PermissionLost on a uri we
     *  can't read). Returns whether the dir was saved. */
    suspend fun saveGameDir(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val granted = runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { Log.w(tag, "takePersistableUriPermission failed", it) }.isSuccess
        if (granted) prefs.setGameDirUri(treeUri.toString())
        granted
    }

    /** The persisted tree uri (for setup_document_file_tree at boot), or null. */
    suspend fun currentGameDirUri(): Uri? = withContext(Dispatchers.IO) {
        val s = prefs.gameDirUri.firstOrNull() ?: return@withContext null
        Uri.parse(s)
    }

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val uriStr = prefs.gameDirUri.firstOrNull() ?: return@withContext ScanResult.NoFolder
        val treeUri = Uri.parse(uriStr)
        // Re-assert the grant on every load (defensive, like load_pref_game_dir).
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { return@withContext ScanResult.PermissionLost }

        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
        if (tree == null || !tree.exists()) return@withContext ScanResult.PermissionLost

        // Hand native THIS process's context + SAF tree (once). The ISO per-game-settings reader
        // mounts the disc via DocumentFile::find/open_fd, which need g_context + the tree -- only
        // the :emu process set them, so without this the ISO title-id read fails in the library.
        if (!nativeSafReady) {
            EmulatorRuntime.emulator?.let {
                it.setup_context(appContext)
                it.setup_document_file_tree(tree)
                nativeSafReady = true
            }
        }

        val games = buildList {
            for (child in tree.listFiles()) {              // ONE level only
                classify(child)?.let { add(it) }
            }
        }.sortedBy { it.name.lowercase() }
        ScanResult.Games(games)
    }

    /** Resolve a game's 8-char uppercase-hex title id for the per-game config path. GOD reads the
     *  container header; ISO mounts the disc + reads default.xex's XEX header; XEX_FOLDER reads
     *  default.xex directly. ZAR has no boot-free reader -> null (caller shows "unavailable").
     *  MUST run off the main thread (these mmap SAF data); requires [scan]'s native-SAF setup. */
    suspend fun readTitleId(ctx: Context, game: Game): String? = withContext(Dispatchers.IO) {
        when (game.format) {
            GameFormat.GOD -> metadata.readGod(ctx, game.launchUri)?.titleId
            GameFormat.ISO, GameFormat.XEX_FOLDER ->
                metadata.readTitleId(ctx, game.launchUri, game.format)
            GameFormat.ZAR -> null
        }
    }

    /** One DocumentFile child -> a Game, or null if ignored/unparseable. */
    private fun classify(child: DocumentFile): Game? {
        val name = child.name ?: return null
        if (child.isDirectory) {
            // XEX folder: launch the default.xex CHILD, display the FOLDER name.
            val xex = child.listFiles().firstOrNull {
                it.isFile && it.name?.lowercase() == "default.xex"
            } ?: return null
            return Game(
                launchUri = xex.uri.toString(),
                name = name,
                format = GameFormat.XEX_FOLDER,
            )
        }
        return when (GameFormat.fromFileName(name)) {
            GameFormat.ISO, GameFormat.ZAR -> {
                val fmt = GameFormat.fromFileName(name)!!
                Game(child.uri.toString(), fmt.displayNameFor(name), fmt)
            }
            GameFormat.GOD -> {
                val uri = child.uri.toString()
                val meta = metadata.readGod(appContext, uri) ?: return null  // not a GOD container
                val iconName = meta.iconPng?.let { iconCache.write(uri, it) }
                Game(uri, meta.name.ifEmpty { name }, GameFormat.GOD, iconName)
            }
            GameFormat.XEX_FOLDER, null -> null
        }
    }
}
