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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val metadataCache: GameMetadataCache,
) {
    private val tag = "GameLibraryRepo"

    /** Set once we've handed the native side this (main/library) process's context + SAF tree,
     *  which the ISO title-id disc-mount needs (g_context + the tree); only :emu set them before. */
    @Volatile private var nativeSafReady = false

    /** Serializes scans so two overlapping refresh()es don't both cold-extract (and race on
     *  the cache file): the second waits, then runs cheap against the now-warm cache. */
    private val scanMutex = Mutex()

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
        // Serialize scans so two overlapping refresh()es can't both cold-extract / race on
        // the cache file; the second waits then runs cheap against the now-warm cache.
        scanMutex.withLock { scanLocked() }
    }

    private suspend fun scanLocked(): ScanResult {
        val uriStr = prefs.gameDirUri.firstOrNull() ?: return ScanResult.NoFolder
        val treeUri = Uri.parse(uriStr)
        // Re-assert the grant on every load (defensive, like load_pref_game_dir).
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { return ScanResult.PermissionLost }

        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
        if (tree == null || !tree.exists()) return ScanResult.PermissionLost

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

        // Load the per-game extraction cache once; mutate during classify; persist once
        // after. On any cache problem classify falls back to extraction, so a cold/corrupt
        // cache still yields identical games.
        metadataCache.load()
        val games = buildList {
            for (child in tree.listFiles()) {              // ONE level only
                classify(child)?.let { add(it) }
            }
        }.sortedBy { it.name.lowercase() }
        // Evict entries for games no longer in the library so the cache stays bounded.
        metadataCache.retainOnly(games.mapTo(HashSet()) { it.launchUri })
        metadataCache.save()
        return ScanResult.Games(games)
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
            // XEX folder: launch the default.xex CHILD. ONE native decompress yields the
            // real XDBF title + icon; fall back to the FOLDER name if the title is unreadable.
            val xex = child.listFiles().firstOrNull {
                it.isFile && it.name?.lowercase() == "default.xex"
            } ?: return null
            val xexUri = xex.uri.toString()
            // Signature off the default.xex CHILD (its bytes are what extraction reads).
            return cachedOrExtract(xexUri, xex, GameFormat.XEX_FOLDER) {
                val meta = metadata.readXexMeta(appContext, xexUri, GameFormat.XEX_FOLDER)
                val displayName = meta?.name?.takeIf { it.isNotEmpty() } ?: name
                val iconName = meta?.iconPng?.let { iconCache.write(xexUri, it) }
                displayName to iconName
            }
        }
        return when (GameFormat.fromFileName(name)) {
            GameFormat.ISO, GameFormat.ZAR -> {
                val fmt = GameFormat.fromFileName(name)!!
                val uri = child.uri.toString()
                if (fmt == GameFormat.ISO) {
                    // ISO: ONE native decompress yields the real XDBF title + icon from
                    // default.xex; fall back to the filename-derived name if title unreadable.
                    cachedOrExtract(uri, child, GameFormat.ISO) {
                        val meta = metadata.readXexMeta(appContext, uri, GameFormat.ISO)
                        val displayName = meta?.name?.takeIf { it.isNotEmpty() }
                            ?: fmt.displayNameFor(name)
                        val iconName = meta?.iconPng?.let { iconCache.write(uri, it) }
                        displayName to iconName
                    }
                } else {
                    // ZAR: no XEX -> no boot-free reader; filename name, no icon. NOT cached
                    // (the filename derive is instant -- nothing to save).
                    Game(uri, fmt.displayNameFor(name), fmt, null)
                }
            }
            GameFormat.GOD -> {
                val uri = child.uri.toString()
                // GOD must distinguish "not a GOD container" (drop the child) from a real
                // miss, so it can't reuse cachedOrExtract's non-null contract: extract first
                // on a cache miss, drop if native says it isn't GOD, else cache the result.
                val cacheHit = metadataCacheHit(uri, child)
                if (cacheHit != null) {
                    return Game(uri, cacheHit.name, GameFormat.GOD, cacheHit.iconCacheName)
                }
                val meta = metadata.readGod(appContext, uri) ?: return null  // not a GOD container
                val displayName = meta.name.ifEmpty { name }
                val iconName = meta.iconPng?.let { iconCache.write(uri, it) }
                metadataCache.put(uri, displayName, iconName, signatureOf(child))
                Game(uri, displayName, GameFormat.GOD, iconName)
            }
            GameFormat.XEX_FOLDER, null -> null
        }
    }

    /** SAF file change signature (size + mtime). May be 0/-1 (unreliable provider) ->
     *  [GameMetadataCache.Signature.cacheable] is then false and the cache won't store it. */
    private fun signatureOf(file: DocumentFile): GameMetadataCache.Signature =
        GameMetadataCache.Signature(sizeBytes = file.length(), lastModified = file.lastModified())

    /** Cache lookup for the extracting branches: a [GameMetadataCache.Decision.Hit]'s
     *  values when the [launchUri] entry is fresh (signature matches + icon File survives),
     *  else null (caller extracts). The icon-existence check uses the real on-disk file. */
    private fun metadataCacheHit(
        launchUri: String,
        file: DocumentFile,
    ): GameMetadataCache.Decision.Hit? {
        val decision = GameMetadataCache.decide(
            cached = metadataCache.get(launchUri),
            signature = signatureOf(file),
            iconFileExists = { iconCache.fileFor(it).exists() },
        )
        return decision as? GameMetadataCache.Decision.Hit
    }

    /** Shared cache wrapper for the always-producing extracting branches (ISO, XEX_FOLDER):
     *  reuse the cached (name, iconCacheName) on a fresh HIT; otherwise run [extract], cache
     *  its result, and build the Game. [extract] returns (displayName, iconCacheName?). */
    private inline fun cachedOrExtract(
        launchUri: String,
        file: DocumentFile,
        format: GameFormat,
        extract: () -> Pair<String, String?>,
    ): Game {
        metadataCacheHit(launchUri, file)?.let {
            return Game(launchUri, it.name, format, it.iconCacheName)
        }
        val (name, iconName) = extract()
        metadataCache.put(launchUri, name, iconName, signatureOf(file))
        return Game(launchUri, name, format, iconName)
    }
}
