package xedroid.compose.core

import android.content.Context
import android.util.Log
import xedroid.compose.Emulator
import xedroid.compose.data.GameFormat

/**
 * Wraps the synchronous SAF+mmap GOD-metadata native call. MUST run off the main
 * thread (Dispatchers.IO) on a thread that can use [ctx]'s ContentResolver.
 * Returns null for any non-GOD/unreadable container (native returns null, not throws,
 * but it is declared `throws RuntimeException` so we defend anyway).
 */
class GameMetadataSource {

    /** Parsed GOD header: title + raw embedded PNG bytes (may be empty []) + the
     *  8-char uppercase-hex title id (null for an unreadable container). */
    data class GodMeta(val name: String, val iconPng: ByteArray?, val titleId: String?)

    fun readGod(ctx: Context, uri: String): GodMeta? {
        val emu = EmulatorRuntime.emulator ?: return null
        return try {
            val info: Emulator.GameInfo = emu.meta_info_from_god_game(ctx, uri) ?: return null
            // info.uri is echoed input; info.fd is always 0 (never read). icon may be
            // a 0-length byte[] -> treat empty as "no icon".
            val icon = info.icon?.takeIf { it.isNotEmpty() }
            GodMeta(name = info.name ?: "", iconPng = icon, titleId = info.titleId)
        } catch (t: RuntimeException) {
            Log.w("GameMetadataSource", "GOD parse failed for $uri", t)
            null
        }
    }

    /** Boot-free title-id read for ISO / XEX_FOLDER via the light native XEX parse.
     *  uri = ISO container uri (ISO) or default.xex child uri (XEX_FOLDER).
     *  Returns null for unsupported formats, unreadable files, or a 00000000 id. */
    fun readTitleId(ctx: Context, uri: String, format: GameFormat): String? {
        val code = format.titleIdCode ?: return null
        val emu = EmulatorRuntime.emulator ?: return null
        return try {
            emu.title_id_from_uri(ctx, uri, code)
                ?.takeIf { it.isNotBlank() && it != "00000000" }
        } catch (t: RuntimeException) {
            Log.w("GameMetadataSource", "title_id read failed for $uri ($format)", t)
            null
        }
    }

    /** Parsed boot-free XEX meta: title (may be "" -> caller uses filename fallback),
     *  raw embedded PNG bytes (null/empty -> app_icon fallback), and the 8-char
     *  uppercase-hex title id (null when unreadable / 00000000). */
    data class XexMeta(val name: String, val iconPng: ByteArray?, val titleId: String?)

    /** Boot-free combined name+icon(+titleId) read for ISO / XEX_FOLDER via a SINGLE native
     *  XEX decompress + XDBF parse. uri = ISO container uri (ISO) or default.xex child uri
     *  (XEX_FOLDER). Returns null for unsupported formats / unreadable files. Individual
     *  fields may still be empty/null (e.g. icon present but title unreadable). Heavier than
     *  readTitleId (full XEX decompress); MUST run off the main thread. */
    fun readXexMeta(ctx: Context, uri: String, format: GameFormat): XexMeta? {
        if (format != GameFormat.ISO && format != GameFormat.XEX_FOLDER) return null
        val code = format.titleIdCode ?: return null
        val emu = EmulatorRuntime.emulator ?: return null
        return try {
            val info: Emulator.GameInfo = emu.meta_from_xex(ctx, uri, code) ?: return null
            XexMeta(
                name = info.name ?: "",                       // null native field -> ""
                iconPng = info.icon?.takeIf { it.isNotEmpty() },
                titleId = info.titleId?.takeIf { it.isNotBlank() && it != "00000000" },
            )
        } catch (t: RuntimeException) {
            Log.w("GameMetadataSource", "XEX meta extraction failed for $uri ($format)", t)
            null
        }
    }
}
