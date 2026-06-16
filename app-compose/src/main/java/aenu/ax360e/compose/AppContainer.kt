package aenu.ax360e.compose

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import aenu.ax360e.compose.core.GameMetadataSource
import aenu.ax360e.compose.data.GameLibraryRepository
import aenu.ax360e.compose.data.GameMetadataCache
import aenu.ax360e.compose.data.IconCache
import aenu.ax360e.compose.data.PreferencesStore
import aenu.ax360e.compose.settings.ConfigStore
import aenu.ax360e.compose.settings.GameSettingsRepository
import aenu.ax360e.compose.settings.GameSettingsViewModel
import aenu.ax360e.compose.settings.SettingsRepository
import aenu.ax360e.compose.settings.SettingsViewModel
import aenu.ax360e.compose.ui.library.GameLibraryViewModel

/** Manual DI (no Hilt). One instance per process, created lazily in MainActivity
 *  from applicationContext (so it survives config changes / outlives any Activity). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val prefs = PreferencesStore(appContext)
    private val keymapStore = aenu.ax360e.compose.data.KeymapStore(appContext)
    private val metadataSource = GameMetadataSource()
    val iconCache = IconCache(appContext.cacheDir)
    // Per-game extraction-result cache, stored alongside game_icons/ in cacheDir so an
    // OS cache-clear wipes the metadata cache AND the icon files together (stay consistent).
    private val metadataCache = GameMetadataCache(appContext.cacheDir)
    val repository = GameLibraryRepository(appContext, prefs, metadataSource, iconCache, metadataCache)

    // ConfigStore is a stateless factory and is safe to share; the SettingsRepository
    // (which owns a single-use ConfigHandle) is built FRESH per ViewModel so one
    // settings VM closing its handle can never pull it out from under another.
    private val configStore = ConfigStore(appContext)

    fun libraryViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == GameLibraryViewModel::class.java) {
                    "Unknown ViewModel ${modelClass.name}"
                }
                return GameLibraryViewModel(repository, iconCache, appContext) as T
            }
        }

    fun settingsViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == SettingsViewModel::class.java) { "Unknown ViewModel ${modelClass.name}" }
                return SettingsViewModel(SettingsRepository(configStore)) as T
            }
        }

    /** Per-game settings VM for one title id. Fresh repo per VM (it owns single-use
     *  ConfigHandles), shared stateless configStore — same rule as settingsViewModelFactory. */
    fun gameSettingsViewModelFactory(titleId: String): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == GameSettingsViewModel::class.java) { "Unknown ViewModel ${modelClass.name}" }
                return GameSettingsViewModel(GameSettingsRepository(configStore, titleId)) as T
            }
        }

    fun keymapViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == aenu.ax360e.compose.ui.keymap.KeymapViewModel::class.java) {
                    "Unknown ViewModel ${modelClass.name}"
                }
                return aenu.ax360e.compose.ui.keymap.KeymapViewModel(keymapStore) as T
            }
        }
}
