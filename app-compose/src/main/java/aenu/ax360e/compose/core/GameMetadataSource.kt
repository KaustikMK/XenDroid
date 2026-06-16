package aenu.ax360e.compose.core

import android.content.Context
import android.util.Log
import aenu.ax360e.Emulator

/**
 * Wraps the synchronous SAF+mmap GOD-metadata native call. MUST run off the main
 * thread (Dispatchers.IO) on a thread that can use [ctx]'s ContentResolver.
 * Returns null for any non-GOD/unreadable container (native returns null, not throws,
 * but it is declared `throws RuntimeException` so we defend anyway).
 */
class GameMetadataSource {

    /** Parsed GOD header: title + raw embedded PNG bytes (may be empty []). */
    data class GodMeta(val name: String, val iconPng: ByteArray?)

    fun readGod(ctx: Context, uri: String): GodMeta? {
        val emu = EmulatorRuntime.emulator ?: return null
        return try {
            val info: Emulator.GameInfo = emu.meta_info_from_god_game(ctx, uri) ?: return null
            // info.uri is echoed input; info.fd is always 0 (never read). icon may be
            // a 0-length byte[] -> treat empty as "no icon".
            val icon = info.icon?.takeIf { it.isNotEmpty() }
            GodMeta(name = info.name ?: "", iconPng = icon)
        } catch (t: RuntimeException) {
            Log.w("GameMetadataSource", "GOD parse failed for $uri", t)
            null
        }
    }
}
