package aenu.ax360e.compose.gamepad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Rounds a fraction to a ~64dp grid. Uses a fixed step count approximating a
 *  long-edge of ~2240dp/64 ≈ 35 steps (the plan's approximation). */
fun snapFrac(f: Float, steps: Int = 35): Float {
    if (steps <= 0) return f
    return (f * steps).roundToInt() / steps.toFloat()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamepadEditorScreen(controller: GamepadController, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val persisted by controller.config.collectAsState(initial = GamepadConfigDto())
    var working by remember(persisted) { mutableStateOf(persisted) }
    var landscape by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<ControlId?>(null) }
    var snap by remember { mutableStateOf(true) }
    var showGlobals by remember { mutableStateOf(false) }
    val base = remember(working, landscape) { controller.controlsFor(working, landscape) }
    // Un-snapped live fraction of the control being dragged. The drag accumulates raw deltas
    // here (NEVER snapped per frame) so it tracks the finger 1:1; snapFrac is applied once on
    // drag-end. Reset explicitly on drag-end. Null when no drag is active.
    var dragRaw by remember { mutableStateOf<Pair<ControlId, Offset>?>(null) }

    fun mutateControls(transform: (List<OnScreenControl>) -> List<OnScreenControl>) {
        val updated = transform(base).toDto()
        working = if (landscape) working.copy(landscape = updated) else working.copy(portrait = updated)
    }
    fun mutateGlobals(transform: (GamepadGlobalsDto) -> GamepadGlobalsDto) {
        working = working.copy(globals = transform(working.globals))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Touch controls") },
                actions = {
                    TextButton(onClick = onDone) { Text("Cancel") }
                    Button(onClick = { scope.launch { controller.save(working); onDone() } },
                        modifier = Modifier.padding(end = 8.dp)) { Text("Save & Quit") }
                }
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = landscape, onClick = { landscape = true },
                        label = { Text("Landscape") }, modifier = Modifier.padding(end = 8.dp))
                    FilterChip(selected = !landscape, onClick = { landscape = false },
                        label = { Text("Portrait") }, modifier = Modifier.padding(end = 8.dp))
                    FilterChip(selected = snap, onClick = { snap = !snap },
                        label = { Text("Snap") }, modifier = Modifier.padding(end = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val def = defaultLayout(landscape).toDto()
                        working = if (landscape) working.copy(landscape = def)
                        else working.copy(portrait = def)
                    }) { Text("Reset") }
                    TextButton(
                        enabled = selected != null,
                        onClick = {
                            val id = selected ?: return@TextButton
                            mutateControls { list ->
                                list.map { if (it.id == id) it.withLayout(vis = !it.visible) else it }
                            }
                        }) { Text("Hide / Show") }
                    TextButton(onClick = { showGlobals = !showGlobals }) { Text("Globals") }
                }
                if (showGlobals) GlobalsEditor(working.globals, ::mutateGlobals)
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            GamepadOverlay(
                controls = base, opacity = working.globals.opacity,
                onKeyEvent = { _, _, _ -> }, editMode = true,
                selectedId = selected, onSelect = { selected = it },
                onTranslate = { id, dx, dy ->
                    // Seed the raw accumulator from the control's CURRENT committed fraction on
                    // the first delta of this drag, then advance it by the raw finger delta.
                    // Never snap here -- snapping the running value re-rounds sub-half-step
                    // deltas back to the same grid cell and freezes the control (the old bug).
                    val cur = dragRaw?.takeIf { it.first == id }?.second
                        ?: base.firstOrNull { it.id == id }
                            ?.let { Offset(it.xFraction, it.yFraction) }
                        ?: return@GamepadOverlay
                    val nx = (cur.x + dx).coerceIn(0.02f, 0.98f)
                    val ny = (cur.y + dy).coerceIn(0.02f, 0.98f)
                    dragRaw = id to Offset(nx, ny)
                    mutateControls { list ->
                        list.map { if (it.id == id) it.withLayout(x = nx, y = ny) else it }
                    }
                },
                onDragEnd = { id ->
                    // Commit the grid-snap once, on release, when Snap is on.
                    val raw = dragRaw?.takeIf { it.first == id }?.second
                    dragRaw = null
                    if (snap && raw != null) {
                        val nx = snapFrac(raw.x.coerceIn(0.02f, 0.98f))
                        val ny = snapFrac(raw.y.coerceIn(0.02f, 0.98f))
                        mutateControls { list ->
                            list.map { if (it.id == id) it.withLayout(x = nx, y = ny) else it }
                        }
                    }
                },
                onScale = { id, f -> mutateControls { list ->
                    list.map { if (it.id == id) it.withLayout(s = (it.scale * f).coerceIn(0.5f, 3f)) else it } } },
            )
        }
    }
}

@Composable
private fun GlobalsEditor(
    globals: GamepadGlobalsDto,
    mutate: ((GamepadGlobalsDto) -> GamepadGlobalsDto) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled", modifier = Modifier.padding(end = 8.dp))
            Switch(checked = globals.enabled, onCheckedChange = { v -> mutate { it.copy(enabled = v) } })
        }
        Text("Opacity: ${(globals.opacity * 100).roundToInt()}%")
        Slider(value = globals.opacity, valueRange = 0.2f..1.0f,
            onValueChange = { v -> mutate { it.copy(opacity = v) } })
        Text("Auto-hide: ${globals.autoHideSeconds.roundToInt()}s (0 = off)")
        Slider(value = globals.autoHideSeconds, valueRange = 0f..20f,
            onValueChange = { v -> mutate { it.copy(autoHideSeconds = v) } })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Haptics", modifier = Modifier.padding(end = 8.dp))
            Switch(checked = globals.hapticsEnabled,
                onCheckedChange = { v -> mutate { it.copy(hapticsEnabled = v) } })
        }
    }
}
