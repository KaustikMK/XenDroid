package aenu.ax360e.compose.settings

import android.content.Context
import aenu.ax360e.Application
import aenu.emulator.Emulator
import java.io.File

/** Sources the live global config file and the bundled default template baseline. */
class ConfigStore(private val appContext: Context) {

    /** The live, editable config the emulator reads at boot. */
    fun globalConfigFile(): File = Application.get_global_config_file()

    /** Open the live config for editing. Bootstraps from the default template if the
     *  file is missing/unparseable (open does NOT auto-create). */
    fun openLive(): ConfigHandle {
        val file = globalConfigFile()
        ensureLiveFileExists(file)
        return try {
            ConfigHandle.openFile(file.absolutePath)
        } catch (e: Emulator.ConfigFileException) {
            // Corrupt/empty file -> reseed from template, retry once.
            file.writeText(defaultTemplateText())
            ConfigHandle.openFile(file.absolutePath)
        }
    }

    /** Read-only baseline parsed from the bundled asset template, for modified-from-default diffing. */
    fun openTemplateBaseline(): ConfigHandle =
        ConfigHandle.openString(defaultTemplateText())

    fun defaultTemplateText(): String = templateText
        ?: loadTemplateText().also { templateText = it }

    private var templateText: String? = null

    private fun loadTemplateText(): String =
        appContext.assets.open("config/default_config.toml").use { it.readBytes().toString(Charsets.UTF_8) }

    private fun ensureLiveFileExists(file: File) {
        if (file.exists()) return
        file.parentFile?.mkdirs()
        // Prefer the on-disk template materialized on first run; fall back to the asset.
        val template = Application.get_default_config_file()
        if (template.exists()) template.copyTo(file, overwrite = false)
        else file.writeText(defaultTemplateText())
    }
}
