package xendroid.compose.core

import xendroid.compose.Utils
import java.io.File

/**
 * Kotlin mirror of the native content-tree layout, used for display + the
 * "already installed?" overwrite check ONLY. The authoritative placement is done
 * natively by InstallContentPackageStandalone (DLC forced under XUID 0, F6/F7/F7a).
 *
 *   content/<XUID 016X>/<TitleID 08X>/<ContentType 08X>/<pkgDir>/...
 *
 * DLC (content_type 0x2) lives under machine XUID 0000000000000000
 * (content_manager.cc:108-112).
 */
object ContentPaths {
    const val DLC_CONTENT_TYPE = 0x00000002
    const val TU_CONTENT_TYPE = 0x000B0000
    const val MACHINE_XUID = "0000000000000000"

    /** <storage_root>/content -- matches the core's content_root derivation
     *  (xendroid_emu.cpp:324-326). Utils.get_storage_root_path() returns the same
     *  absolute external app-data path passed to the core as --storage_root. */
    fun contentRoot(): File = File(Utils.get_storage_root_path(), "content")

    /** content/0000000000000000/<TITLE_ID>/<CONTENT_TYPE 08X> under the machine XUID. */
    fun contentDir(titleId: String, contentType: Int): File =
        File(contentRoot(), "$MACHINE_XUID/${titleId.uppercase()}/%08X".format(contentType))

    fun dlcDir(titleId: String): File = contentDir(titleId, DLC_CONTENT_TYPE)
}
