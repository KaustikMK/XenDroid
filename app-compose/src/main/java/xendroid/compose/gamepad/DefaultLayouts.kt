package xendroid.compose.gamepad

fun defaultLayout(landscape: Boolean): List<OnScreenControl> {
    // Vertical band where controls sit; portrait packs them lower.
    val yL = if (landscape) 0.78f else 0.74f   // bottom row
    val yU = if (landscape) 0.16f else 0.50f   // shoulders/triggers row
    return listOf(
        OnScreenControl.Dpad(ControlId.DPAD, xFraction = 0.13f, yFraction = yL),
        OnScreenControl.AnalogStick(
            ControlId.LEFT_STICK,
            isLeft = true,
            xFraction = 0.30f,
            yFraction = yL - 0.04f
        ),
        // Face diamond (A bottom, B right, X left, Y top) bottom-right.
        OnScreenControl.Button(ControlId.A, Kc.A, "A", 0.88f, yL),
        OnScreenControl.Button(ControlId.B, Kc.B, "B", 0.95f, yL - 0.10f),
        OnScreenControl.Button(ControlId.X, Kc.X, "X", 0.81f, yL - 0.10f),
        OnScreenControl.Button(ControlId.Y, Kc.Y, "Y", 0.88f, yL - 0.20f),
        OnScreenControl.AnalogStick(
            ControlId.RIGHT_STICK,
            isLeft = false,
            xFraction = 0.70f,
            yFraction = yL - 0.04f
        ),
        // Shoulders + triggers (triggers outermost).
        OnScreenControl.Button(ControlId.LB, Kc.SHOULDER_L, "LB", 0.10f, yU),
        OnScreenControl.Button(ControlId.LT, Kc.TRIGGER_L, "LT", 0.04f, yU - 0.10f),
        OnScreenControl.Button(ControlId.RB, Kc.SHOULDER_R, "RB", 0.90f, yU),
        OnScreenControl.Button(ControlId.RT, Kc.TRIGGER_R, "RT", 0.96f, yU - 0.10f),
        // Back / Start center.
        OnScreenControl.Button(ControlId.BACK, Kc.BACK, "◀", 0.43f, 0.93f, baseSizeDp = 48f),
        OnScreenControl.Button(ControlId.START, Kc.START, "☰", 0.57f, 0.93f, baseSizeDp = 48f),
        // Stick clicks (small, near the sticks).
        OnScreenControl.Button(
            ControlId.LS_CLICK,
            Kc.THUMB_PRESS_L,
            "L3",
            0.30f,
            yL + 0.08f,
            baseSizeDp = 40f
        ),
        OnScreenControl.Button(
            ControlId.RS_CLICK,
            Kc.THUMB_PRESS_R,
            "R3",
            0.70f,
            yL + 0.08f,
            baseSizeDp = 40f
        ),
    )
}
