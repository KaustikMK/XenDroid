package xedroid.compose

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import xedroid.compose.core.GameMetadataSource
import xedroid.compose.data.GameLibraryRepository
import xedroid.compose.data.GameMetadataCache
import xedroid.compose.data.IconCache
import xedroid.compose.data.PreferencesStore
import xedroid.compose.settings.ConfigStore
import xedroid.compose.settings.GameSettingsRepository
import xedroid.compose.settings.GameSettingsViewModel
import xedroid.compose.settings.SettingsRepository
import xedroid.compose.settings.SettingsViewModel
import xedroid.compose.ui.library.GameLibraryViewModel
import xedroid.compose.data.KeymapStore
import xedroid.compose.ui.keymap.KeymapViewModel

/** Manual DI (no Hilt). One instance per process, created lazily in MainActivity
 *  from applicationContext (so it survives config changes / outlives any Activity). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val prefs = PreferencesStore(appContext)
    private val keymapStore = KeymapStore(appContext)
    private val metadataSource = GameMetadataSource()
    val iconCache = IconCache(appContext.cacheDir)
    // Per-game extraction-result cache, stored alongside game_icons/ in cacheDir so an
    // OS cache-clear wipes the metadata cache AND the icon files together (stay consistent).
    private val metadataCache = GameMetadataCache(appContext.cacheDir)
    val repository =
        GameLibraryRepository(appContext, prefs, metadataSource, iconCache, metadataCache)

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
                require(modelClass == KeymapViewModel::class.java) {
                    "Unknown ViewModel ${modelClass.name}"
                }
                return KeymapViewModel(keymapStore) as T
            }
        }
}
