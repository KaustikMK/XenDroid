package xedroid.compose.data

import kotlinx.serialization.Serializable

/**
 * One library entry. [launchUri] is the per-format URI handed to the emulator:
 *  - ISO/ZAR: the file's own SAF uri
 *  - XEX folder: the default.xex CHILD uri (NOT the folder uri)
 *  - GOD: the container uri (echoed by native meta_info_from_god_game)
 * [iconCacheName] is the cacheDir filename of the extracted PNG blob, or null
 * (-> app_icon fallback). GOD reads the icon from the container header; ISO and
 * XEX folders extract it from default.xex's XDBF resource. ZAR has no icon.
 */
@Serializable
data class Game(
    val launchUri: String,
    val name: String,
    val format: GameFormat,
    val iconCacheName: String? = null,
) {
    /** Stable id derived from the launch uri (used for shortcut ids, list keys). */
    val stableId: String get() = launchUri
}
