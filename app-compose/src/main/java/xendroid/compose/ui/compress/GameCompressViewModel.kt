package xendroid.compose.ui.compress

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xendroid.compose.core.EmulatorRuntime
import xendroid.compose.data.GameLibraryRepository
import java.io.File
import java.io.IOException

/**
 * Drives the per-game "Compress to .zar" safe-replace flow. The native
 * [xendroid.compose.Emulator.compressIsoToZar] mounts the ISO by its SAF uri,
 * packs its VFS into a temp .zar AND verifies it (re-opens the archive and
 * compares file-count + total bytes against the source disc); it returns
 * X_STATUS, 0 == success. Only after that verified .zar is physically placed
 * in the game folder is the original .iso deleted. Every failure / early-return
 * path leaves the .iso intact. The native call blocks (VFS walk + zstd +
 * verify), so it runs on [Dispatchers.IO]; the temp .zar lives in cacheDir and
 * is always removed in a finally.
 */
class GameCompressViewModel(
    private val appContext: Context,
    private val repo: GameLibraryRepository,
) : ViewModel() {

    sealed interface CompressState {
        data object Idle : CompressState
        /** A running step: "Compressing…" / "Verifying…" / "Replacing…". */
        data class Busy(val message: String, val progress: Float = -1f) : CompressState
        data class Done(val message: String) : CompressState
        /** Any failure; the .iso is untouched. */
        data class Failed(val message: String) : CompressState
    }

    private val _state = MutableStateFlow<CompressState>(CompressState.Idle)
    val state: StateFlow<CompressState> = _state.asStateFlow()

    fun dismiss() { _state.value = CompressState.Idle }

    /**
     * Compress the ISO at [launchUri] (a content:// SAF uri) into a verified
     * .zar in the same game folder, then delete the .iso — and ONLY then.
     */
    fun compress(launchUri: String) = viewModelScope.launch {
        _state.value = CompressState.Busy("Compressing…", 0f)
        // Poll native compress progress while the pack phase blocks an IO thread
        // (the getter reads file-static atomics, so concurrent reads are safe).
        val poll = launch {
            while (isActive) {
                val p = EmulatorRuntime.emulator?.compressProgress() ?: 0f
                (_state.value as? CompressState.Busy)
                    ?.takeIf { it.message == "Compressing…" }
                    ?.let { _state.value = it.copy(progress = p.coerceIn(0f, 1f)) }
                delay(200)
            }
        }
        val result = withContext(Dispatchers.IO) { runCompress(launchUri) }
        poll.cancel()
        _state.value = result
    }

    private suspend fun runCompress(launchUri: String): CompressState {
        EmulatorRuntime.ensureLoaded()
        val emulator = EmulatorRuntime.emulator
            ?: return CompressState.Failed("Emulator not loaded. .iso is unchanged.")

        // Resolve-only prologue — NOTHING is mutated yet.
        val isoUri = Uri.parse(launchUri)
        val isoDoc = DocumentFile.fromSingleUri(appContext, isoUri)
            ?: return CompressState.Failed("Couldn't open the ISO. .iso is unchanged.")
        val gameDir = repo.currentGameDirUri()
            ?: return CompressState.Failed("Set a game folder first. .iso is unchanged.")
        val tree = DocumentFile.fromTreeUri(appContext, gameDir)?.takeIf { it.canWrite() }
            ?: return CompressState.Failed("Game folder isn't writable. .iso is unchanged.")

        val isoName = isoDoc.name ?: "game.iso"
        val baseName = isoName.removeSuffix(".iso").removeSuffix(".ISO").ifBlank { "game" }
        val zarName = "$baseName.zar"
        if (tree.findFile(zarName) != null)
            return CompressState.Failed("A “$zarName” already exists in the folder. .iso is unchanged.")

        val tempZar = File.createTempFile("compress", ".zar", appContext.cacheDir)
        tempZar.delete()   // native creates the output; it must not pre-exist
        var placed: DocumentFile? = null
        try {
            // (1) PACK + VERIFY: native mounts the ISO by uri, walks its VFS into the
            //     temp .zar, then re-opens + verifies it. 0 == created AND verified.
            val packStatus = emulator.compressIsoToZar(launchUri, tempZar.absolutePath)
            if (packStatus != 0)
                return CompressState.Failed(
                    "Compression failed (0x${packStatus.toUInt().toString(16)}). .iso is unchanged.")

            // (3) PLACE: copy the verified temp .zar INTO the game folder. .iso STILL present.
            _state.value = CompressState.Busy("Replacing…")
            val dest = tree.createFile("application/octet-stream", zarName)
                ?: return CompressState.Failed("Couldn't create the .zar in the folder. .iso is unchanged.")
            placed = dest
            val copied = runCatching {
                appContext.contentResolver.openOutputStream(dest.uri, "wt")?.use { os ->
                    tempZar.inputStream().use { it.copyTo(os) }
                } ?: throw IOException("no output stream")
            }.isSuccess
            if (!copied) {
                dest.delete(); placed = null
                return CompressState.Failed("Couldn't write the .zar. .iso is unchanged.")
            }
            // (3b) sanity: the placed .zar is non-empty and its size matches the temp.
            if (dest.length() <= 0L || dest.length() != tempZar.length()) {
                dest.delete(); placed = null
                return CompressState.Failed("The written .zar looks incomplete. .iso is unchanged.")
            }

            // (4) DELETE THE ISO — and ONLY NOW. A verified .zar is already in the folder.
            val isoDeleted = DocumentFile.fromSingleUri(appContext, isoUri)?.delete() == true
            return if (!isoDeleted)
                CompressState.Done(
                    "Created “$zarName”, but the .iso couldn't be deleted automatically. " +
                        "You can delete it manually.")
            else
                CompressState.Done("Replaced the .iso with “$zarName”.")
        } catch (e: Exception) {
            placed?.delete()   // threw AFTER placing but BEFORE deleting iso -> remove orphan .zar
            return CompressState.Failed("Compression failed: ${e.message}. .iso is unchanged.")
        } finally {
            tempZar.delete()   // always clean the cacheDir temp
        }
    }
}
