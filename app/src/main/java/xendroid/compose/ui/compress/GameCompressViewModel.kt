package xendroid.compose.ui.compress

import android.content.Context
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
import java.io.File

/**
 * Drives the per-game "Compress to .zar" safe-replace flow. The native
 * [xendroid.compose.Emulator.compressIsoToZar] mounts the ISO by its absolute
 * host path, packs its VFS into a temp .zar AND verifies it (re-opens the
 * archive and compares file-count + total bytes against the source disc); it
 * returns X_STATUS, 0 == success. Only after that verified .zar is physically
 * placed beside the .iso is the original .iso deleted. Every failure /
 * early-return path leaves the .iso intact. The native call blocks (VFS walk +
 * zstd + verify), so it runs on [Dispatchers.IO]; the temp .zar lives in
 * cacheDir and is always removed in a finally.
 */
class GameCompressViewModel(
    private val appContext: Context,
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
     * Compress the ISO at [launchUri] (an absolute host path) into a verified
     * .zar beside the .iso, then delete the .iso — and ONLY then.
     */
    fun compress(launchUri: String) = viewModelScope.launch {
        _state.value = CompressState.Busy("Compressing…", 0f)
        // Poll native compress progress while the pack stage blocks an IO thread
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
        val isoFile = File(launchUri)
        if (!isoFile.isFile)
            return CompressState.Failed("Couldn't open the ISO. .iso is unchanged.")
        // Place the .zar beside the .iso (the "same folder" semantics of the old SAF flow).
        val parent = isoFile.parentFile?.takeIf { it.canWrite() }
            ?: return CompressState.Failed("Game folder isn't writable. .iso is unchanged.")

        val isoName = isoFile.name
        val baseName = isoName.removeSuffix(".iso").removeSuffix(".ISO").ifBlank { "game" }
        val zarName = "$baseName.zar"
        if (File(parent, zarName).exists())
            return CompressState.Failed("A “$zarName” already exists in the folder. .iso is unchanged.")

        val tempZar = File.createTempFile("compress", ".zar", appContext.cacheDir)
        tempZar.delete()   // native creates the output; it must not pre-exist
        var placed: File? = null
        try {
            // (1) PACK + VERIFY: native mounts the ISO by path, walks its VFS into the
            //     temp .zar, then re-opens + verifies it. 0 == created AND verified.
            val packStatus = emulator.compressIsoToZar(isoFile.absolutePath, tempZar.absolutePath)
            if (packStatus != 0)
                return CompressState.Failed(
                    "Compression failed (0x${packStatus.toUInt().toString(16)}). .iso is unchanged.")

            // (3) PLACE: copy the verified temp .zar BESIDE the .iso. .iso STILL present.
            _state.value = CompressState.Busy("Replacing…")
            val dest = File(parent, zarName)
            val copied = runCatching { tempZar.copyTo(dest, overwrite = false) }.isSuccess
            if (!copied) {
                dest.delete(); placed = null
                return CompressState.Failed("Couldn't write the .zar. .iso is unchanged.")
            }
            placed = dest
            // (3b) sanity: the placed .zar is non-empty and its size matches the temp.
            if (dest.length() <= 0L || dest.length() != tempZar.length()) {
                dest.delete(); placed = null
                return CompressState.Failed("The written .zar looks incomplete. .iso is unchanged.")
            }

            // (4) DELETE THE ISO — and ONLY NOW. A verified .zar is already in the folder.
            val isoDeleted = isoFile.delete()
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
