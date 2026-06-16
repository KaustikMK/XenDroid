package aenu.ax360e.compose.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ax360e_prefs")

/** Persists the SAF tree uri (replaces legacy PREF_GAME_DIR="game_dir" in
 *  default SharedPreferences). Key name kept identical for parity/clarity. */
class PreferencesStore(private val appContext: Context) {

    private val gameDirKey = stringPreferencesKey("game_dir")

    val gameDirUri: Flow<String?> =
        appContext.dataStore.data.map { it[gameDirKey] }

    suspend fun setGameDirUri(uri: String) {
        appContext.dataStore.edit { it[gameDirKey] = uri }
    }
}
