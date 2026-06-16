package aenu.ax360e.compose

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import aenu.ax360e.compose.core.GameMetadataSource
import aenu.ax360e.compose.data.GameLibraryRepository
import aenu.ax360e.compose.data.IconCache
import aenu.ax360e.compose.data.PreferencesStore
import aenu.ax360e.compose.ui.library.GameLibraryViewModel

/** Manual DI (no Hilt). One instance per process, created lazily in MainActivity
 *  from applicationContext (so it survives config changes / outlives any Activity). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val prefs = PreferencesStore(appContext)
    private val metadataSource = GameMetadataSource()
    val iconCache = IconCache(appContext.cacheDir)
    val repository = GameLibraryRepository(appContext, prefs, metadataSource, iconCache)

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
}
