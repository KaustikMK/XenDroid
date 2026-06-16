package aenu.ax360e.compose.data

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-Kotlin checks on the SP3 keymap default table. KeyEvent.KEYCODE_* are
 *  compile-time int constants, inlined into GameButtons, so they resolve to real
 *  values in a plain JVM unit test (no android.jar method dispatch). */
class GameButtonsTest {

    @Test fun has_sixteen_buttons() =
        assertEquals(16, GameButtons.ALL.size)

    @Test fun index_and_keyCode_are_identity_0_to_15() {
        GameButtons.ALL.forEachIndexed { i, b ->
            assertEquals(i, b.index)
            assertEquals(i, b.keyCode)
        }
    }

    @Test fun triggers_are_unbound_by_default() {
        // KeyMapConfig.DEFAULT_KEYMAPPERS indices 14/15 == 0 (Left/Right Trigger).
        assertEquals(0, GameButtons.ALL[14].defaultAndroidKey)
        assertEquals(0, GameButtons.ALL[15].defaultAndroidKey)
    }

    @Test fun default_lookup_drops_unbound_triggers() {
        // 14 bound buttons; the 2 triggers (default 0) are excluded.
        assertEquals(14, GameButtons.DEFAULT_LOOKUP.size)
        assertFalse(GameButtons.DEFAULT_LOOKUP.containsKey(0))
    }

    @Test fun default_lookup_maps_android_key_to_game_key_code() {
        // DPAD_LEFT (android) -> game KEY_CODE 0; BUTTON_A -> 4; BUTTON_R1 -> 11.
        assertEquals(0, GameButtons.DEFAULT_LOOKUP[KeyEvent.KEYCODE_DPAD_LEFT])
        assertEquals(4, GameButtons.DEFAULT_LOOKUP[KeyEvent.KEYCODE_BUTTON_A])
        assertEquals(11, GameButtons.DEFAULT_LOOKUP[KeyEvent.KEYCODE_BUTTON_R1])
    }

    @Test fun l2_r2_default_to_thumb_press_not_triggers() {
        // Legacy default: KEYCODE_BUTTON_L2/R2 (104/105) -> Left/Right Thumb Press
        // (game KEY_CODE 12/13), NOT the triggers.
        assertEquals(12, GameButtons.DEFAULT_LOOKUP[KeyEvent.KEYCODE_BUTTON_L2])
        assertEquals(13, GameButtons.DEFAULT_LOOKUP[KeyEvent.KEYCODE_BUTTON_R2])
    }

    @Test fun every_button_has_a_label() =
        assertTrue(GameButtons.ALL.all { it.label.isNotBlank() })
}
