package xendroid.compose.gamepad

fun defaultLayout(landscape: Boolean): List<OnScreenControl> {
    // Controls sit in a vertical band; t in [0,1] maps top->bottom of that band.
    val yTop = if (landscape) 0.12f else 0.50f
    val ySpan = if (landscape) 0.81f else 0.45f
    fun y(t: Float) = yTop + ySpan * t

    // Xbox 360 grouping; primary controls (sticks/face) sit low where the thumbs rest.
    val fx = 0.86f  // face cluster center x
    return listOf(
        // Left column.
        OnScreenControl.Button(ControlId.LB, Kc.SHOULDER_L, "LB", 0.06f, y(0f)),
        OnScreenControl.Button(ControlId.LT, Kc.TRIGGER_L, "LT", 0.15f, y(0f)),
        OnScreenControl.Dpad(ControlId.DPAD, xFraction = 0.13f, yFraction = y(0.40f)),
        OnScreenControl.AnalogStick(
            ControlId.LEFT_STICK, isLeft = true, xFraction = 0.14f, yFraction = y(0.82f)
        ),
        OnScreenControl.Button(
            ControlId.LS_CLICK, Kc.THUMB_PRESS_L, "L3", 0.045f, y(0.82f), baseSizeDp = 40f
        ),
        // Right column.
        OnScreenControl.Button(ControlId.RB, Kc.SHOULDER_R, "RB", 0.94f, y(0f)),
        OnScreenControl.Button(ControlId.RT, Kc.TRIGGER_R, "RT", 0.85f, y(0f)),
        OnScreenControl.AnalogStick(
            ControlId.RIGHT_STICK, isLeft = false, xFraction = fx, yFraction = y(0.40f)
        ),
        OnScreenControl.Button(
            ControlId.RS_CLICK, Kc.THUMB_PRESS_R, "R3", 0.955f, y(0.40f), baseSizeDp = 40f
        ),
        // Face diamond (A bottom, B right, X left, Y top), bottom-right.
        OnScreenControl.Button(ControlId.Y, Kc.Y, "Y", fx, y(0.68f)),
        OnScreenControl.Button(ControlId.X, Kc.X, "X", fx - 0.06f, y(0.82f)),
        OnScreenControl.Button(ControlId.B, Kc.B, "B", fx + 0.06f, y(0.82f)),
        OnScreenControl.Button(ControlId.A, Kc.A, "A", fx, y(0.96f)),
        // Back / Start center.
        OnScreenControl.Button(ControlId.BACK, Kc.BACK, "◀", 0.44f, y(1f), baseSizeDp = 48f),
        OnScreenControl.Button(ControlId.START, Kc.START, "☰", 0.56f, y(1f), baseSizeDp = 48f),
    ).map { it.withLayout(s = 0.75f) }
}
