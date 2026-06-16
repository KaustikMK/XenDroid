package aenu.ax360e.compose.settings

import android.content.Context
import aenu.emulator.Emulator
import java.io.File

/** Snapshot of one setting's current persisted value (raw String) + modified flag. */
data class SettingValue(val raw: String?, val modified: Boolean)

/**
 * Pure (JNI-free) modified-from-default logic, extracted for unit testing. Both the
 * live and the template raw value fall back to the schema default before comparing,
 * so an absent (null) key never NPEs and never spuriously reads as "modified".
 * Encodes the EmulatorSettings.java:591 NPE fix as a regression guard.
 */
fun modified(effectiveLive: String?, effectiveTemplate: String?, schemaDefault: String): Boolean {
    val live = effectiveLive ?: schemaDefault
    val template = effectiveTemplate ?: schemaDefault
    return live != template
}

class SettingsRepository(private val store: ConfigStore) {

    private var live: ConfigHandle? = null
    /** key -> template default (raw String), captured once at load. */
    private var templateDefaults: Map<String, String?> = emptyMap()

    val isCustomDriverSupported: Boolean
        get() = runCatching { File("/dev/kgsl-3d0").exists() }.getOrDefault(false)

    /** Open the live config + read template defaults. Call once per screen entry.
     *  @Synchronized so the off-main load() and the lifecycle pause/resume flush can't
     *  race on `live` (double-open leak / open-during-close). */
    @Synchronized
    fun open() {
        if (live != null) return
        live = store.openLive()
        // Read defaults from the bundled template baseline, then close it (string handle).
        val baseline = store.openTemplateBaseline()
        templateDefaults = SettingsSchema.allSettings.associate { s ->
            s.key to baseline.getString(s.section, s.name)
        }
        baseline.closeString()  // free the string handle (we don't persist it)
    }

    /** Re-open after a pause-flush nulled the handle. No-op if already open. */
    @Synchronized
    fun ensureOpen() { if (live == null) open() }

    private fun handle(): ConfigHandle = checkNotNull(live) { "SettingsRepository not open()ed" }

    // Reads are null-safe against an un-open()ed handle: the screen composes rows
    // before the async open() (which waits on ensureLoaded off-main) completes, so a
    // not-yet-open repo returns schema defaults rather than crashing on handle().
    fun rawValue(s: Setting): String? = live?.getString(s.section, s.name)

    fun valueOf(s: Setting): SettingValue {
        val raw = rawValue(s)
        return SettingValue(raw, modified = isModified(s, raw))
    }

    /** Null-safe modified check: compare the effective live value (raw ?: schema default)
     *  against the template default (template ?: schema default). Never NPEs on absent keys. */
    private fun isModified(s: Setting, raw: String?): Boolean =
        modified(raw, templateDefaults[s.key], schemaDefaultString(s))

    private fun schemaDefaultString(s: Setting): String = when (s) {
        is Setting.Bool      -> if (s.default) "true" else "false"
        is Setting.IntRange  -> s.default.toString()
        is Setting.ListChoice -> s.default
        is Setting.Action    -> s.default
    }

    // ---- Typed reads with schema-default fallback (null-safe vs un-open()ed handle) ----
    fun boolOf(s: Setting.Bool): Boolean = live?.getBool(s.section, s.name, s.default) ?: s.default
    fun intOf(s: Setting.IntRange): Int = live?.getInt(s.section, s.name, s.default) ?: s.default
    fun listValueOf(s: Setting.ListChoice): String =
        live?.getString(s.section, s.name) ?: s.default
    fun stringOf(s: Setting): String = live?.getString(s.section, s.name) ?: ""

    // ---- Writes (in-memory; persisted on close) ----
    fun setBool(s: Setting.Bool, v: Boolean) = handle().putBool(s.section, s.name, v)
    fun setInt(s: Setting.IntRange, v: Int) =
        handle().putInt(s.section, s.name, v.coerceIn(s.min, s.max))
    fun setListValue(s: Setting.ListChoice, value: String) =
        handle().putString(s.section, s.name, value)
    fun setRawString(s: Setting, value: String) =
        handle().putString(s.section, s.name, value)

    /** Persist edits to disk + free the handle. Safe to call multiple times. */
    @Synchronized
    fun flushAndClose() {
        live?.closeFile()   // idempotent; the ONLY disk write
        live = null
    }
}
