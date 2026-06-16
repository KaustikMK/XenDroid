package aenu.ax360e.compose.gamepad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/** Pure layout math: control center in px for the given surface size. */
fun controlCenterPx(c: OnScreenControl, size: IntSize): Offset =
    Offset(c.xFraction * size.width, c.yFraction * size.height)

/** Pure layout math: hit radius in px. baseSizeDp is a diameter, so radius = dp/2 * scale.
 *  Sticks/dpad get a slightly larger grab radius (1.15x). */
fun controlRadiusPx(c: OnScreenControl, density: Density): Float {
    val base = with(density) { c.baseSizeDp.dp.toPx() } / 2f * c.scale
    return when (c) {
        is OnScreenControl.AnalogStick, is OnScreenControl.Dpad -> base * 1.15f
        else -> base
    }
}

/** Pure hit-test: first visible control whose center is within its radius of pos. */
fun hitTest(
    controls: List<OnScreenControl>, pos: Offset, size: IntSize, density: Density,
): OnScreenControl? = controls.firstOrNull { c ->
    if (!c.visible) return@firstOrNull false
    val center = controlCenterPx(c, size)
    hypot(pos.x - center.x, pos.y - center.y) <= controlRadiusPx(c, density)
}

@Composable
fun GamepadOverlay(
    controls: List<OnScreenControl>,
    opacity: Float,
    onKeyEvent: (Int, Boolean, Int) -> Unit,
    modifier: Modifier = Modifier,
    onUserInteraction: () -> Unit = {},   // resets auto-hide timer
    editMode: Boolean = false,
    selectedId: ControlId? = null,
    onSelect: (ControlId?) -> Unit = {},
    onTranslate: (ControlId, dxFrac: Float, dyFrac: Float) -> Unit = { _, _, _ -> },
    onScale: (ControlId, factor: Float) -> Unit = { _, _ -> },
) {
    // Create the emitter ONCE. The host's onKeyEvent lambda is unstable (captures the
    // Activity), so remember(onKeyEvent) would recreate the emitter on every touch (poke
    // -> tick++ -> recomposition) -- which, with the dispose-release below, would fire
    // releaseAll() on EVERY touch and make the pad feel dead. rememberUpdatedState keeps
    // the sink current without churning the emitter.
    val latestOnKeyEvent by rememberUpdatedState(onKeyEvent)
    val emitter = remember { GamepadEmitter { code, pressed, value -> latestOnKeyEvent(code, pressed, value) } }
    // PointerId.value -> claimed control; survives recomposition.
    val claims = remember { mutableMapOf<Long, ControlId>() }
    // PointerId.value -> last position, for drawing stick knobs at the finger.
    val pointerPos = remember { mutableMapOf<Long, Offset>() }
    // per-dpad last-pressed sector set, for diffing.
    val dpadState = remember { mutableMapOf<ControlId, Set<Int>>() }
    val density = LocalDensity.current
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    // On teardown (overlay leaves composition: emulator exit / booted->false), release
    // every code so a control held at dispose can't stick. Keyed on Unit so it fires ONLY
    // at real disposal -- NOT on every recomposition (which would release mid-press).
    DisposableEffect(Unit) { onDispose { emitter.releaseAll() } }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { sizePx = it }
            .pointerInput(controls, sizePx, editMode, selectedId) {
                if (editMode) {
                    editPointerLoop(controls, sizePx, density, onSelect, onTranslate, onScale)
                } else {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            onUserInteraction()
                            for (ch in ev.changes) {
                                val pid = ch.id.value
                                when {
                                    ch.changedToDownIgnoreConsumed() -> {
                                        val hit = hitTest(controls, ch.position, sizePx, density)
                                        if (hit != null) {
                                            claims[pid] = hit.id
                                            pointerPos[pid] = ch.position
                                            dispatchDown(emitter, hit, ch.position, sizePx, density, dpadState)
                                            ch.consume()
                                        }
                                    }
                                    // up OR cancellation (Home/focus-loss sends ACTION_CANCEL,
                                    // which is !pressed but not changedToUp) -> release the claim.
                                    !ch.pressed -> {
                                        pointerPos.remove(pid)
                                        claims.remove(pid)?.let { id ->
                                            controls.firstOrNull { it.id == id }?.let {
                                                dispatchUp(emitter, it, dpadState)
                                            }
                                            ch.consume()
                                        }
                                    }
                                    ch.pressed -> {            // MOVE on a claimed pointer
                                        val id = claims[pid] ?: continue
                                        pointerPos[pid] = ch.position
                                        controls.firstOrNull { it.id == id }?.let {
                                            dispatchMove(emitter, it, ch.position, sizePx, density, dpadState)
                                            ch.consume()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        // Reverse lookup: claimed control id -> active pointer position (for stick knob).
        val activePos: (ControlId) -> Offset? = { id ->
            val pid = claims.entries.firstOrNull { it.value == id }?.key
            pid?.let { pointerPos[it] }
        }
        controls.filter { it.visible }.forEach {
            drawControl(it, opacity, sizePx, density, activePos(it.id))
            if (editMode && it.id == selectedId) drawSelection(it, sizePx, density)
        }
    }
}

/** Edit-mode pointer loop: DOWN selects + claims; single-pointer drag translates the
 *  claimed control; two-pointer pinch scales the selected control by span ratio. */
private suspend fun PointerInputScope.editPointerLoop(
    controls: List<OnScreenControl>,
    size: IntSize,
    density: Density,
    onSelect: (ControlId?) -> Unit,
    onTranslate: (ControlId, Float, Float) -> Unit,
    onScale: (ControlId, Float) -> Unit,
) {
    var dragId: ControlId? = null
    var lastDrag: Offset? = null
    var lastSpan: Float? = null
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            // DOWN: hit-test + select + start a drag on the hit control.
            ev.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let { ch ->
                val hit = hitTest(controls, ch.position, size, density)
                onSelect(hit?.id)
                dragId = hit?.id
                lastDrag = if (hit != null) ch.position else null
                lastSpan = null
                if (hit != null) ch.consume()
            }
            when {
                pressed.size >= 2 && dragId != null -> {
                    // Pinch: scale the selected control by the span ratio.
                    val a = pressed[0].position; val b = pressed[1].position
                    val span = hypot(a.x - b.x, a.y - b.y)
                    val prev = lastSpan
                    if (prev != null && prev > 0f) onScale(dragId!!, span / prev)
                    lastSpan = span
                    lastDrag = null
                    pressed.forEach { it.consume() }
                }
                pressed.size == 1 && dragId != null -> {
                    val ch = pressed[0]
                    val prev = lastDrag
                    if (prev != null && size.width > 0 && size.height > 0) {
                        val dx = (ch.position.x - prev.x) / size.width
                        val dy = (ch.position.y - prev.y) / size.height
                        if (dx != 0f || dy != 0f) onTranslate(dragId!!, dx, dy)
                    }
                    lastDrag = ch.position
                    lastSpan = null
                    ch.consume()
                }
                pressed.isEmpty() -> { dragId = null; lastDrag = null; lastSpan = null }
            }
        }
    }
}

// ---- Gameplay dispatch (down/move/up) ----

private fun dispatchDown(
    emitter: GamepadEmitter, c: OnScreenControl, pos: Offset,
    size: IntSize, density: Density, dpadState: MutableMap<ControlId, Set<Int>>,
) {
    when (c) {
        is OnScreenControl.Button -> emitter.pressDigital(c.keyCode)
        is OnScreenControl.Dpad -> updateDpad(emitter, c, pos, size, density, dpadState)
        is OnScreenControl.AnalogStick -> updateStick(emitter, c, pos, size, density)
    }
}

private fun dispatchMove(
    emitter: GamepadEmitter, c: OnScreenControl, pos: Offset,
    size: IntSize, density: Density, dpadState: MutableMap<ControlId, Set<Int>>,
) {
    when (c) {
        is OnScreenControl.Button -> Unit                 // sticky press while held
        is OnScreenControl.Dpad -> updateDpad(emitter, c, pos, size, density, dpadState)
        is OnScreenControl.AnalogStick -> updateStick(emitter, c, pos, size, density)
    }
}

private fun dispatchUp(
    emitter: GamepadEmitter, c: OnScreenControl, dpadState: MutableMap<ControlId, Set<Int>>,
) {
    when (c) {
        is OnScreenControl.Button -> emitter.releaseDigital(c.keyCode)
        is OnScreenControl.Dpad -> {
            emitter.applyDpad(dpadState[c.id] ?: emptySet(), emptySet())
            emitter.releaseAllDpad()
            dpadState[c.id] = emptySet()
        }
        is OnScreenControl.AnalogStick -> emitter.releaseStick(c.isLeft)
    }
}

private fun updateDpad(
    emitter: GamepadEmitter, c: OnScreenControl.Dpad, pos: Offset,
    size: IntSize, density: Density, dpadState: MutableMap<ControlId, Set<Int>>,
) {
    val center = controlCenterPx(c, size)
    val radius = controlRadiusPx(c, density)
    val now = emitter.dpadSectors(pos.x - center.x, pos.y - center.y, deadzone = radius * 0.30f)
    emitter.applyDpad(dpadState[c.id] ?: emptySet(), now)
    dpadState[c.id] = now
}

private fun updateStick(
    emitter: GamepadEmitter, c: OnScreenControl.AnalogStick, pos: Offset,
    size: IntSize, density: Density,
) {
    val center = controlCenterPx(c, size)
    val radius = controlRadiusPx(c, density)
    var dxN = (pos.x - center.x) / radius
    var dyN = (pos.y - center.y) / radius
    // Radial dead-zone: ignore tiny wobble near center, then rescale so output ramps
    // from 0 just past the dead-zone (smooth, no jump). PPSSPP defers dead-zone to the
    // emulator, but a small source dead-zone makes the on-screen stick far less twitchy.
    val r = hypot(dxN, dyN)
    val dead = 0.12f
    if (r < dead) { emitter.releaseStick(c.isLeft); return }
    val rescale = ((r - dead) / (1f - dead)) / r
    dxN *= rescale; dyN *= rescale
    emitter.stick(c.isLeft, dxN, dyN)        // emitter circular-clamps
}

// ---- Drawing ----

private fun DrawScope.drawControl(
    c: OnScreenControl, opacity: Float, size: IntSize, density: Density, activePos: Offset?,
) {
    val center = controlCenterPx(c, size)
    val radius = with(density) { c.baseSizeDp.dp.toPx() } / 2f * c.scale
    val fill = Color.White.copy(alpha = 0.12f * opacity)
    val line = Color.White.copy(alpha = 0.55f * opacity)
    val strokeW = with(density) { 2.dp.toPx() }
    when (c) {
        is OnScreenControl.Button -> {
            drawCircle(fill, radius, center)
            drawCircle(line, radius, center, style = Stroke(strokeW))
            drawLabel(c.label, center, radius, line)
        }
        is OnScreenControl.Dpad -> {
            // A plus made of two rounded bars.
            val arm = radius
            val w = radius * 0.42f
            drawCircle(fill, radius, center)
            drawCircle(line, radius, center, style = Stroke(strokeW))
            drawLine(line, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), w)
            drawLine(line, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), w)
        }
        is OnScreenControl.AnalogStick -> {
            drawCircle(fill, radius, center)
            drawCircle(line, radius, center, style = Stroke(strokeW))
            // Inner knob offset toward the active pointer, clamped to the ring.
            val knob = activePos?.let { p ->
                var dx = p.x - center.x; var dy = p.y - center.y
                val len = hypot(dx, dy)
                if (len > radius) { dx = dx / len * radius; dy = dy / len * radius }
                Offset(center.x + dx, center.y + dy)
            } ?: center
            drawCircle(line.copy(alpha = 0.7f * opacity), radius * 0.5f, knob)
        }
    }
}

private fun DrawScope.drawSelection(c: OnScreenControl, size: IntSize, density: Density) {
    val center = controlCenterPx(c, size)
    val radius = with(density) { c.baseSizeDp.dp.toPx() } / 2f * c.scale
    val ring = with(density) { 3.dp.toPx() }
    drawCircle(Color(0xFF4FC3F7), radius + ring, center, style = Stroke(ring))
}

private fun DrawScope.drawLabel(label: String, center: Offset, radius: Float, color: Color) {
    if (label.isEmpty()) return
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.argb(
                (color.alpha * 255).toInt(), 255, 255, 255)
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = radius * 0.7f
        }
        val fm = paint.fontMetrics
        val baseline = center.y - (fm.ascent + fm.descent) / 2f
        canvas.nativeCanvas.drawText(label, center.x, baseline, paint)
    }
}
