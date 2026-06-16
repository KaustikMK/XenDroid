package aenu.ax360e.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import java.util.Locale
import aenu.ax360e.compose.core.EmulatorSession

/**
 * Top-left FPS / frame-time readout drawn over the Vulkan SurfaceView. Shows the
 * INSTANT fps (1000/last-frame-ms), not the smoothed average. Polls the native
 * lock-free frame-stats atomics on a coroutine at [pollHz]; the SurfaceView keeps
 * presenting underneath regardless of this cadence.
 *
 * Visibility is owned by the caller via [visible] (e.g. the show_debug_overlay
 * setting) — the composable itself only renders when visible to avoid running the
 * poll loop while hidden.
 */
@Composable
fun FpsOverlay(
    session: EmulatorSession,
    visible: Boolean,
    modifier: Modifier = Modifier,
    pollHz: Int = 4,
) {
    if (!visible) return

    var fps by remember { mutableStateOf(0.0) }
    var frameMs by remember { mutableStateOf(0.0) }

    // ~4 Hz poll (250 ms) — matches the legacy overlay cadence and is enough for a
    // human-readable readout while costing ~nothing. Restarts if pollHz changes.
    LaunchedEffect(pollHz) {
        val periodMs = (1000L / pollHz.coerceIn(1, 30))
        while (true) {
            fps = session.instantFps()
            frameMs = session.lastFrameTimeMs()
            delay(periodMs)
        }
    }

    Box(modifier) {
        Text(
            text = String.format(Locale.US, "FPS %.0f\n%.1f ms", fps, frameMs),
            color = Color(0xFFFFFF00),                 // yellow, like the legacy overlay
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Color(0x80000000))         // semi-transparent backing for legibility
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
