package xendroid.compose.gamepad

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

class GamepadController(appContext: Context) {
    private val store = GamepadLayoutStore(appContext)
    val config: Flow<GamepadConfigDto> = store.config
    suspend fun save(cfg: GamepadConfigDto) = store.save(cfg)

    /** Runtime controls for an orientation = defaults merged with persisted layout. */
    fun controlsFor(cfg: GamepadConfigDto, landscape: Boolean): List<OnScreenControl> {
        val base = defaultLayout(landscape)
        val dto = if (landscape) cfg.landscape else cfg.portrait
        return dto.applyTo(base)
    }
}

@Composable
fun rememberAutoHide(autoHideSeconds: Float): Pair<Boolean, () -> Unit> {
    var visible by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    val poke = remember { { visible = true; tick++; Unit } }
    if (autoHideSeconds > 0f) {
        LaunchedEffect(tick, autoHideSeconds) {
            delay((autoHideSeconds * 1000).toLong())
            visible = false       // overlay then animates alpha to 0 over 0.5s
        }
    }
    return visible to poke
}
