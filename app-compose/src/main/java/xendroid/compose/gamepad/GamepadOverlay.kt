package xendroid.compose.gamepad

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
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
    gridStepsX: Int = 0,                  // editor: snap-grid cell count per axis (0 = no grid).
    gridStepsY: Int = 0,                  // x/y differ so the cells are square on a non-1:1 screen.
    selectedId: ControlId? = null,
    onSelect: (ControlId?) -> Unit = {},
    onTranslate: (ControlId, dxFrac: Float, dyFrac: Float) -> Unit = { _, _, _ -> },
    onScale: (ControlId, factor: Float) -> Unit = { _, _ -> },
    onDragEnd: (ControlId) -> Unit = {},
) {
    // Create the emitter ONCE. The host's onKeyEvent lambda is unstable (captures the
    // Activity), so remember(onKeyEvent) would recreate the emitter on every touch (poke
    // -> tick++ -> recomposition) -- which, with the dispose-release below, would fire
    // releaseAll() on EVERY touch and make the pad feel dead. rememberUpdatedState keeps
    // the sink current without churning the emitter.
    val latestOnKeyEvent by rememberUpdatedState(onKeyEvent)
    val emitter = remember { GamepadEmitter { code, pressed, value -> latestOnKeyEvent(code, pressed, value) } }
    // Observable (mutableStateMapOf) so the Canvas re-draws the stick knob on every move/
    // claim change -- plain maps record no snapshot read, so the knob would sit frozen.
    val claims = remember { mutableStateMapOf<Long, ControlId>() }
    val pointerPos = remember { mutableStateMapOf<Long, Offset>() }
    // per-dpad last-pressed sector set, for diffing.
    val dpadState = remember { mutableMapOf<ControlId, Set<Int>>() }
    val density = LocalDensity.current
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    // Latest controls WITHOUT restarting the pointerInput: in edit mode every drag frame
    // produces a new `controls` list; if it keyed the pointerInput, the gesture would cancel
    // mid-drag (the button moves one frame then stutters/stops). Read this inside instead.
    val controlsState = rememberUpdatedState(controls)
    // Edit callbacks must stay CURRENT for the long-lived pointer coroutine: pointerInput is
    // keyed on (editMode, sizePx) and is NOT relaunched per drag frame, so a raw capture would
    // freeze them to the gesture-start composition (the dragged control would only ever move by
    // one frame's delta from its origin). Mirror the onKeyEvent pattern.
    val onSelectState = rememberUpdatedState(onSelect)
    val onTranslateState = rememberUpdatedState(onTranslate)
    val onScaleState = rememberUpdatedState(onScale)
    val onDragEndState = rememberUpdatedState(onDragEnd)

    // On teardown (overlay leaves composition: emulator exit / booted->false), release
    // every code so a control held at dispose can't stick. Keyed on Unit so it fires ONLY
    // at real disposal -- NOT on every recomposition (which would release mid-press).
    DisposableEffect(Unit) { onDispose { emitter.releaseAll() } }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { sizePx = it }
            // Keyed only on editMode+sizePx (stable during a gesture). controls is read live
            // via controlsState so a drag (which mutates controls every frame) never restarts
            // the gesture. selectedId is not needed here (only the draw uses it).
            .pointerInput(editMode, sizePx) {
                if (editMode) {
                    editPointerLoop(
                        controlsState, sizePx, density,
                        { id -> onSelectState.value(id) },
                        { id, dx, dy -> onTranslateState.value(id, dx, dy) },
                        { id, f -> onScaleState.value(id, f) },
                        { id -> onDragEndState.value(id) },
                    )
                } else {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            onUserInteraction()
                            for (ch in ev.changes) {
                                val pid = ch.id.value
                                when {
                                    ch.changedToDownIgnoreConsumed() -> {
                                        val hit = hitTest(controlsState.value, ch.position, sizePx, density)
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
                                            // Only release if NO other finger still holds this
                                            // control (two fingers on one stick: lifting one
                                            // must not zero the input the other is still driving).
                                            if (claims.none { it.value == id }) {
                                                controlsState.value.firstOrNull { it.id == id }?.let {
                                                    dispatchUp(emitter, it, dpadState)
                                                }
                                            }
                                            ch.consume()
                                        }
                                    }
                                    ch.pressed -> {            // MOVE on a claimed pointer
                                        val id = claims[pid] ?: continue
                                        pointerPos[pid] = ch.position
                                        controlsState.value.firstOrNull { it.id == id }?.let {
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
        // Auto-hidden (alpha animated to 0): emit NO draw ops. The overlay stays MOUNTED
        // (pointerInput above keeps catching the wake tap / held-release), but the Canvas
        // must not record a full-screen layer of alpha-0 shapes -- that keeps the host
        // ComposeView window content-bearing, so SurfaceFlinger GPU-composites it over the
        // game SurfaceView EVERY refresh, forever. That is the "slight, constant" stutter:
        // legacy parity was the empty host window (its gamepad was a separate SurfaceView).
        // Skipping draws here leaves the window transparent -> the present is punched
        // straight through to the game layer, no per-frame blend.
        if (opacity <= 0.01f) return@Canvas
        // Editor snap grid: faint lines so "Snap" visibly aligns controls to a grid. The caller
        // passes per-axis cell counts (computed from the actual screen size) so the cells are
        // SQUARE rather than stretched. Drawn behind the controls; edit mode only.
        if (editMode && gridStepsX > 0 && gridStepsY > 0 && sizePx.width > 0 && sizePx.height > 0) {
            val grid = Color.White.copy(alpha = 0.16f)
            for (i in 1 until gridStepsX) {
                val x = sizePx.width.toFloat() * i / gridStepsX
                drawLine(grid, Offset(x, 0f), Offset(x, sizePx.height.toFloat()), strokeWidth = 1f)
            }
            for (j in 1 until gridStepsY) {
                val y = sizePx.height.toFloat() * j / gridStepsY
                drawLine(grid, Offset(0f, y), Offset(sizePx.width.toFloat(), y), strokeWidth = 1f)
            }
        }
        // Reverse lookup: claimed control id -> active pointer position (for stick knob).
        val activePos: (ControlId) -> Offset? = { id ->
            claims.entries.lastOrNull { it.value == id }?.key?.let { pointerPos[it] }
        }
        val claimedIds = claims.values.toSet()
        controls.filter { it.visible }.forEach { c ->
            drawControl(
                c, opacity, sizePx, density,
                pressed = c.id in claimedIds,
                dpadDirs = if (c is OnScreenControl.Dpad) dpadState[c.id] ?: emptySet() else emptySet(),
                activePos = activePos(c.id),
            )
            if (editMode && c.id == selectedId) drawSelection(c, sizePx, density)
        }
    }
}

/** Edit-mode pointer loop: DOWN selects + claims; single-pointer drag translates the
 *  claimed control; two-pointer pinch scales the selected control by span ratio.
 *  onDragEnd fires once when a single-finger drag is released, so the editor can snap
 *  the final resting position to the grid (snap is NOT applied per frame -- that would
 *  round every sub-half-step delta back to the same cell and freeze the control). */
private suspend fun PointerInputScope.editPointerLoop(
    controlsState: State<List<OnScreenControl>>,
    size: IntSize,
    density: Density,
    onSelect: (ControlId?) -> Unit,
    onTranslate: (ControlId, Float, Float) -> Unit,
    onScale: (ControlId, Float) -> Unit,
    onDragEnd: (ControlId) -> Unit,
) {
    var dragId: ControlId? = null
    var lastDrag: Offset? = null
    var lastSpan: Float? = null
    var didDrag = false                         // a single-finger translate actually happened
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            // DOWN: hit-test + select + start a drag on the hit control.
            ev.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let { ch ->
                val hit = hitTest(controlsState.value, ch.position, size, density)
                onSelect(hit?.id)
                dragId = hit?.id
                lastDrag = if (hit != null) ch.position else null
                lastSpan = null
                didDrag = false
                if (hit != null) ch.consume()
            }
            when {
                pressed.size >= 2 && dragId != null -> {
                    // Pinch: scale the selected control by the span ratio. A pinch ends the
                    // single-finger drag phase WITHOUT committing a snap (no position change).
                    val a = pressed[0].position; val b = pressed[1].position
                    val span = hypot(a.x - b.x, a.y - b.y)
                    val prev = lastSpan
                    if (prev != null && prev > 0f) onScale(dragId!!, span / prev)
                    lastSpan = span
                    lastDrag = null
                    didDrag = false
                    pressed.forEach { it.consume() }
                }
                pressed.size == 1 && dragId != null -> {
                    val ch = pressed[0]
                    val prev = lastDrag
                    if (prev != null && size.width > 0 && size.height > 0) {
                        val dx = (ch.position.x - prev.x) / size.width
                        val dy = (ch.position.y - prev.y) / size.height
                        if (dx != 0f || dy != 0f) { onTranslate(dragId!!, dx, dy); didDrag = true }
                    }
                    lastDrag = ch.position
                    lastSpan = null
                    ch.consume()
                }
                pressed.isEmpty() -> {
                    // Pointer(s) lifted: commit the grid-snap of the final position (once).
                    val ended = dragId
                    if (ended != null && didDrag) onDragEnd(ended)
                    dragId = null; lastDrag = null; lastSpan = null; didDrag = false
                }
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
    // Normalize the touch offset to [-1,1] across the pad (half-extent = the visual radius),
    // then the 3x3 grid decides the direction (legacy "press the arm of the cross").
    val radius = with(density) { c.baseSizeDp.dp.toPx() } / 2f * c.scale
    val nx = (pos.x - center.x) / radius
    val ny = (pos.y - center.y) / radius
    val now = emitter.dpadSectors(nx, ny)
    emitter.applyDpad(dpadState[c.id] ?: emptySet(), now)
    dpadState[c.id] = now
}

private fun updateStick(
    emitter: GamepadEmitter, c: OnScreenControl.AnalogStick, pos: Offset,
    size: IntSize, density: Density,
) {
    val center = controlCenterPx(c, size)
    // Normalize by the VISUAL ring (matches drawControl), NOT the 1.15x grab radius, so
    // full deflection == reaching the drawn ring (and the knob == the emitted value).
    val radius = with(density) { c.baseSizeDp.dp.toPx() } / 2f * c.scale
    val dxN = (pos.x - center.x) / radius
    val dyN = (pos.y - center.y) / radius
    // No source dead-zone (PPSSPP/legacy have none -- the emulator owns thumbstick
    // dead-zone; a second one here double-dead-zones). Release happens only on touch-up.
    emitter.stick(c.isLeft, dxN, dyN)        // emitter circular-clamps
}

// ---- Drawing ----

// Xbox face-button colours (A green, B red, X blue, Y yellow).
private val XBOX_GREEN = Color(0xFF5EAE3A)
private val XBOX_RED = Color(0xFFC23B3B)
private val XBOX_BLUE = Color(0xFF3E78C2)
private val XBOX_YELLOW = Color(0xFFD8A21E)
private val PILL_IDS = setOf(ControlId.LB, ControlId.RB, ControlId.LT, ControlId.RT)

private fun DrawScope.drawControl(
    c: OnScreenControl, opacity: Float, size: IntSize, density: Density,
    pressed: Boolean, dpadDirs: Set<Int>, activePos: Offset?,
) {
    val center = controlCenterPx(c, size)
    val radius = with(density) { c.baseSizeDp.dp.toPx() } / 2f * c.scale
    val strokeW = with(density) { 2.dp.toPx() }
    when (c) {
        is OnScreenControl.Button -> drawButton(c, center, radius, strokeW, opacity, pressed)
        is OnScreenControl.Dpad -> drawDpad(center, radius, strokeW, opacity, dpadDirs)
        is OnScreenControl.AnalogStick -> drawStick(center, radius, strokeW, opacity, activePos)
    }
}

private fun DrawScope.drawButton(
    c: OnScreenControl.Button, center: Offset, radius: Float, strokeW: Float,
    opacity: Float, pressed: Boolean,
) {
    fun white(a: Float) = Color.White.copy(alpha = a * opacity)
    val face = when (c.id) {
        ControlId.A -> XBOX_GREEN; ControlId.B -> XBOX_RED
        ControlId.X -> XBOX_BLUE; ControlId.Y -> XBOX_YELLOW
        else -> null
    }
    when {
        face != null -> {                                   // round colour face button + letter
            drawCircle(face.copy(alpha = (if (pressed) 1f else 0.82f) * opacity), radius, center)
            drawCircle(white(0.55f), radius, center, style = Stroke(strokeW))
            if (pressed) drawCircle(white(0.9f), radius + strokeW, center, style = Stroke(strokeW))
            drawLabel(c.label, center, radius, white(0.95f), bold = true)
        }
        c.id in PILL_IDS -> {                               // bumper/trigger: rounded pill
            val w = radius * 2.1f; val h = radius * 1.15f
            val tl = Offset(center.x - w / 2f, center.y - h / 2f)
            val cr = CornerRadius(h / 2f, h / 2f)
            drawRoundRect(white(if (pressed) 0.42f else 0.18f), tl, Size(w, h), cr)
            drawRoundRect(white(0.5f), tl, Size(w, h), cr, style = Stroke(strokeW))
            drawLabel(c.label, center, radius, white(0.85f))
        }
        else -> {                                           // L3/R3/Back/Start: small circle
            val rr = radius * 0.82f
            drawCircle(white(if (pressed) 0.42f else 0.16f), rr, center)
            drawCircle(white(0.5f), rr, center, style = Stroke(strokeW))
            drawLabel(c.label, center, rr, white(0.8f))
        }
    }
}

/** Original compose d-pad look: four OUTLINED "pennant" arrows (rounded base at the outer
 *  edge, tapering to a point toward the center), idle white, pressed arm lights up gold. */
private fun DrawScope.drawDpad(
    center: Offset, radius: Float, strokeW: Float, opacity: Float, dirs: Set<Int>,
) {
    val arm = dpadArmPath(center, radius)           // the LEFT arm; rotate for the others
    val idle = Color.White.copy(alpha = 0.5f * opacity)
    // Pressed/active highlight: white, matching every other button (was gold/yellow).
    val lit = Color.White
    // (code, rotation): LEFT base at left/tip toward center; +90 each step -> UP/RIGHT/DOWN.
    val arms = listOf(Kc.DPAD_LEFT to 0f, Kc.DPAD_UP to 90f, Kc.DPAD_RIGHT to 180f, Kc.DPAD_DOWN to 270f)
    for ((_, angle) in arms) rotate(angle, center) { drawPath(arm, idle, style = Stroke(strokeW)) }
    for ((code, angle) in arms) if (code in dirs) rotate(angle, center) {
        drawPath(arm, lit.copy(alpha = 0.22f * opacity))                        // faint fill
        drawPath(arm, lit.copy(alpha = 0.95f * opacity), style = Stroke(strokeW * 1.4f))
    }
}

/** The LEFT arm: a pennant from the outer-left edge tapering to a point near the center.
 *  Rotated 90/180/270 about the center to make UP/RIGHT/DOWN. */
private fun dpadArmPath(center: Offset, radius: Float): Path {
    val hw = radius * 0.30f
    val baseX = center.x - radius
    val midX = center.x - radius * 0.42f      // where the rectangle starts tapering
    val tipX = center.x - radius * 0.06f      // the point, near center
    val corner = radius * 0.10f
    val top = center.y - hw; val bot = center.y + hw
    return Path().apply {
        moveTo(baseX + corner, top)
        lineTo(midX, top)
        lineTo(tipX, center.y)
        lineTo(midX, bot)
        lineTo(baseX + corner, bot)
        quadraticBezierTo(baseX, bot, baseX, bot - corner)
        lineTo(baseX, top + corner)
        quadraticBezierTo(baseX, top, baseX + corner, top)
        close()
    }
}

private fun DrawScope.drawStick(
    center: Offset, radius: Float, strokeW: Float, opacity: Float, activePos: Offset?,
) {
    drawCircle(Color.White.copy(alpha = 0.10f * opacity), radius, center)                 // dish
    drawCircle(Color.White.copy(alpha = 0.45f * opacity), radius, center, style = Stroke(strokeW)) // range ring
    val knob = activePos?.let { p ->
        var dx = p.x - center.x; var dy = p.y - center.y
        val len = hypot(dx, dy)
        if (len > radius) { dx = dx / len * radius; dy = dy / len * radius }
        Offset(center.x + dx, center.y + dy)
    } ?: center
    val active = activePos != null
    val knobR = radius * 0.52f
    drawCircle(Color.White.copy(alpha = (if (active) 0.5f else 0.32f) * opacity), knobR, knob)
    drawCircle(Color.White.copy(alpha = 0.7f * opacity), knobR, knob, style = Stroke(strokeW))
}

private fun DrawScope.drawSelection(c: OnScreenControl, size: IntSize, density: Density) {
    val center = controlCenterPx(c, size)
    val radius = with(density) { c.baseSizeDp.dp.toPx() } / 2f * c.scale
    val ring = with(density) { 3.dp.toPx() }
    drawCircle(Color(0xFF4FC3F7), radius + ring, center, style = Stroke(ring))
}

private fun DrawScope.drawLabel(
    label: String, center: Offset, radius: Float, color: Color, bold: Boolean = false,
) {
    if (label.isEmpty()) return
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            isAntiAlias = true
            isFakeBoldText = bold
            this.color = android.graphics.Color.argb(
                (color.alpha * 255).toInt(), 255, 255, 255)
            textAlign = Paint.Align.LEFT
            textSize = radius * 0.7f
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(label, 0, label.length, bounds)
        val opticalDx = if (label == "◀") -bounds.width() / 6f else 0f
        val fm = paint.fontMetrics
        val baseline = center.y - (fm.ascent + fm.descent) / 2f
        canvas.nativeCanvas.drawText(label, center.x - bounds.exactCenterX() + opticalDx, baseline, paint)
    }
}
