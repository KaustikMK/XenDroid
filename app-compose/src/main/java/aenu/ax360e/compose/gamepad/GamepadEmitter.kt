package aenu.ax360e.compose.gamepad

import kotlin.math.atan2
import kotlin.math.hypot

class GamepadEmitter(private val sink: (Int, Boolean, Int) -> Unit) {

    fun pressDigital(code: Int) = sink(code, true, Kc.VALUE_UNUSED)
    fun releaseDigital(code: Int) = sink(code, false, Kc.VALUE_UNUSED)

    /** One axis -> opposing half-axis codes. d already de-inverted for X;
     *  caller passes the Y component pre-negated (X360 up positive). */
    private fun emitAxisPair(d: Float, negKey: Int, posKey: Int) {
        when {
            d < 0f -> { sink(posKey, false, 0); sink(negKey, true, (d * 32768f).toInt()) }
            d > 0f -> { sink(negKey, false, 0); sink(posKey, true, (d * 32767f).toInt()) }
            else   -> { sink(negKey, false, 0); sink(posKey, false, 0) }
        }
    }

    /** Stick: dxN/dyN are normalized [-1,1] in screen space (dy down-positive).
     *  Circular-clamp BEFORE encode; negate Y for X360 up-positive. */
    fun stick(isLeft: Boolean, dxN: Float, dyN: Float) {
        var x = dxN; var y = dyN
        val len = hypot(x, y)
        if (len > 1f) { x /= len; y /= len }
        val (lx, ly, rx, ry) = if (isLeft)
            intArrayOf(Kc.LTHUMB_LEFT, Kc.LTHUMB_UP, Kc.LTHUMB_RIGHT, Kc.LTHUMB_DOWN)
        else intArrayOf(Kc.RTHUMB_LEFT, Kc.RTHUMB_UP, Kc.RTHUMB_RIGHT, Kc.RTHUMB_DOWN)
        emitAxisPair(x, negKey = lx, posKey = rx)       // X: left=neg, right=pos
        emitAxisPair(-y, negKey = ly, posKey = ry)      // Y negated: up=neg, down=pos
    }

    fun releaseStick(isLeft: Boolean) {
        val codes = if (isLeft) intArrayOf(16, 17, 18, 19) else intArrayOf(20, 21, 22, 23)
        codes.forEach { sink(it, false, 0) }
    }

    /** D-pad radial 8-sector: dx,dy in screen space relative to center; deadzone in px²
     *  passed as normalized len. Returns the set of pressed dpad codes; caller diffs
     *  against previous to emit press/release. */
    fun dpadSectors(dx: Float, dy: Float, deadzone: Float): Set<Int> {
        if (hypot(dx, dy) < deadzone) return emptySet()
        // atan2(-dy, dx): screen-up is -dy; 0 rad = RIGHT, +pi/2 = UP.
        val ang = atan2(-dy, dx)                          // [-pi, pi]
        val deg = (Math.toDegrees(ang.toDouble()) + 360.0) % 360.0
        // 8 sectors of 45°, centered on each cardinal/diagonal.
        return when (((deg + 22.5) / 45.0).toInt() % 8) {
            0 -> setOf(Kc.DPAD_RIGHT)
            1 -> setOf(Kc.DPAD_RIGHT, Kc.DPAD_UP)
            2 -> setOf(Kc.DPAD_UP)
            3 -> setOf(Kc.DPAD_UP, Kc.DPAD_LEFT)
            4 -> setOf(Kc.DPAD_LEFT)
            5 -> setOf(Kc.DPAD_LEFT, Kc.DPAD_DOWN)
            6 -> setOf(Kc.DPAD_DOWN)
            else -> setOf(Kc.DPAD_DOWN, Kc.DPAD_RIGHT)
        }
    }

    fun applyDpad(prev: Set<Int>, now: Set<Int>) {
        (prev - now).forEach { sink(it, false, Kc.VALUE_UNUSED) }
        (now - prev).forEach { sink(it, true, Kc.VALUE_UNUSED) }
    }
    fun releaseAllDpad() = listOf(0, 1, 2, 3).forEach { sink(it, false, Kc.VALUE_UNUSED) }

    /** Release EVERY gamepad code (digital 0..15, analog half-axes 16..23). Called on
     *  overlay teardown so a control held at dispose time can never stick. */
    fun releaseAll() {
        for (code in 0..15) sink(code, false, Kc.VALUE_UNUSED)
        for (code in 16..23) sink(code, false, 0)
    }
}
