package xendroid.compose.core

import android.util.Log
import xendroid.compose.Emulator
import xendroid.compose.data.GameFormat

/**
 * Wraps the synchronous mmap-based native metadata reads off an absolute host path
 * (All Files Access / real-path mode). MUST run off the main thread (Dispatchers.IO).
 * Returns null for any non-matching/unreadable container (native returns null, not throws,
 * but it is declared `throws RuntimeException` so we defend anyway).
 */
class GameMetadataSource {

    /** Parsed GOD header: title + raw embedded PNG bytes (may be empty []) + the
     *  8-char uppercase-hex title id (null for an unreadable container). */
    data class GodMeta(val name: String, val iconPng: ByteArray?, val titleId: String?)

    /** Parsed boot-free XEX meta: title (may be "" -> caller uses filename fallback),
     *  raw embedded PNG bytes (null/empty -> app_icon fallback), and the 8-char
     *  uppercase-hex title id (null when unreadable / 00000000). */
    data class XexMeta(val name: String, val iconPng: ByteArray?, val titleId: String?)

    // ---- Real-path (All Files Access) reads: call the path natives (no Context: real-path
    // devices mount directly from a path). path = the absolute host path (ISO file /
    // default.xex / .zar / GOD container).

    /** Boot-free title-id read from a real path for ISO / XEX_FOLDER / ZAR. */
    fun readTitleIdPath(path: String, format: GameFormat): String? {
        val code = format.titleIdCode ?: return null
        val emu = EmulatorRuntime.emulator ?: return null
        return try {
            emu.title_id_from_path(path, code)
                ?.takeIf { it.isNotBlank() && it != "00000000" }
        } catch (t: RuntimeException) {
            Log.w("GameMetadataSource", "title_id read failed for $path ($format)", t)
            null
        } catch (t: LinkageError) {
            warnMissingNative("title_id_from_path", t); null
        }
    }

    /** Boot-free combined name+icon(+titleId) read from a real path for ISO/XEX_FOLDER/ZAR. */
    fun readXexMetaPath(path: String, format: GameFormat): XexMeta? {
        if (format != GameFormat.ISO && format != GameFormat.XEX_FOLDER &&
            format != GameFormat.ZAR) return null
        val code = format.titleIdCode ?: return null
        val emu = EmulatorRuntime.emulator ?: return null
        return try {
            val info: Emulator.GameInfo = emu.meta_from_path(path, code) ?: return null
            XexMeta(
                name = info.name ?: "",
                iconPng = info.icon?.takeIf { it.isNotEmpty() },
                titleId = info.titleId?.takeIf { it.isNotBlank() && it != "00000000" },
            )
        } catch (t: RuntimeException) {
            Log.w("GameMetadataSource", "XEX meta extraction failed for $path ($format)", t)
            null
        } catch (t: LinkageError) {
            warnMissingNative("meta_from_path", t); null
        }
    }

    /** GOD container header read from a real path (title + icon + title id). */
    fun readGodPath(path: String): GodMeta? {
        val emu = EmulatorRuntime.emulator ?: return null
        return try {
            val info: Emulator.GameInfo = emu.meta_info_from_god_path(path) ?: return null
            val icon = info.icon?.takeIf { it.isNotEmpty() }
            GodMeta(name = info.name ?: "", iconPng = icon, titleId = info.titleId)
        } catch (t: RuntimeException) {
            Log.w("GameMetadataSource", "GOD parse failed for $path", t)
            null
        } catch (t: LinkageError) {
            warnMissingNative("meta_info_from_god_path", t); null
        }
    }

    /** The path scan natives (section 1A) may not be built into libe.so yet -- a call then
     *  throws UnsatisfiedLinkError (a LinkageError, NOT a RuntimeException). Swallow it so the
     *  real-path scan degrades to filename + app_icon (the shippable v1 tier) instead of
     *  crashing the whole scan. Remove the LinkageError catches once the natives ship. */
    private fun warnMissingNative(method: String, t: LinkageError) {
        runCatching { Log.w("GameMetadataSource", "native $method unavailable (not built yet)", t) }
    }
}
