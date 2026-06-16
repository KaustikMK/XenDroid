package aenu.ax360e.compose.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import aenu.ax360e.compose.core.EmulatorRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {

    val categories: List<SettingsCategory> = SettingsSchema.categories
    val isCustomDriverSupported: Boolean get() = repo.isCustomDriverSupported

    private val _values = MutableStateFlow<Map<String, SettingValue>>(emptyMap())
    val values: StateFlow<Map<String, SettingValue>> = _values.asStateFlow()

    init { load() }

    /** Single off-main load path (shared by init + onResume): ensureLoaded() can sleep +
     *  System.loadLibrary on delay-load devices (Adreno 5xx/6xx) where Application.onCreate
     *  skips the eager load, so the native Config calls must wait on it OFF the main thread.
     *  repo.ensureOpen() is @Synchronized + idempotent, so concurrent init/onResume loads
     *  cannot double-open. */
    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            EmulatorRuntime.ensureLoaded()
            repo.ensureOpen()
            reloadAll()
        }
    }

    private fun reloadAll() {
        _values.value = SettingsSchema.allSettings.associate { it.key to repo.valueOf(it) }
    }

    private fun refreshKey(s: Setting) {
        _values.value = _values.value.toMutableMap().apply { put(s.key, repo.valueOf(s)) }
    }

    fun onBoolChanged(s: Setting.Bool, v: Boolean) { repo.setBool(s, v); refreshKey(s) }
    fun onIntChanged(s: Setting.IntRange, v: Int) { repo.setInt(s, v); refreshKey(s) }
    fun onListChanged(s: Setting.ListChoice, value: String) { repo.setListValue(s, value); refreshKey(s) }
    /** Custom driver picker writes the installed .so path ("" clears -> system driver).
     *  Persisted durably OFF the screen handle (the SAF picker pauses the screen, nulling
     *  the handle), then the snapshot is refreshed. Runs off the main thread. */
    fun onDriverPathChanged(s: Setting.Action, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.persistDriverPath(value)
                repo.ensureOpen()
                reloadAll()
            }.onFailure { android.util.Log.w("SettingsViewModel", "driver path persist failed", it) }
        }
    }

    fun currentBool(s: Setting.Bool) = repo.boolOf(s)
    fun currentInt(s: Setting.IntRange) = repo.intOf(s)
    fun currentListValue(s: Setting.ListChoice) = repo.listValueOf(s)
    fun currentDriverPath(s: Setting.Action) = repo.stringOf(s)

    /** Durable write. Call from the screen on lifecycle pause and on dispose. */
    fun flush() = repo.flushAndClose()

    /** Re-open the handle after a pause-flush and refresh snapshots. Call on resume. */
    fun onResume() = load()

    override fun onCleared() { repo.flushAndClose() }
}
