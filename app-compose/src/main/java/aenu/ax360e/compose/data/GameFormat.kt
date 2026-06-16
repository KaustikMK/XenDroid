package aenu.ax360e.compose.data

/**
 * Container formats the one-level library scan recognizes, mirroring legacy
 * Filter.is_iso_file / is_zar_file / is_god_game (MainActivity.java:449-469).
 * XEX_FOLDER is handled separately (it's a directory, not a filename match).
 */
enum class GameFormat {
    ISO, ZAR, GOD, XEX_FOLDER;

    /** Display name from a *file* name (XEX folders use the folder name verbatim). */
    fun displayNameFor(fileName: String): String = when (this) {
        ISO, ZAR -> fileName.dropLast(4)   // strip ".iso"/".zar"
        GOD, XEX_FOLDER -> fileName
    }

    companion object {
        /** File-name -> format, or null if it's an ignored file. Detection order
         *  matches the scan: .iso, then .zar, then extensionless => GOD. */
        fun fromFileName(name: String): GameFormat? {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".iso") -> ISO
                lower.endsWith(".zar") -> ZAR
                !name.contains('.') -> GOD   // is_god_game: indexOf('.') == -1
                else -> null
            }
        }
    }
}
