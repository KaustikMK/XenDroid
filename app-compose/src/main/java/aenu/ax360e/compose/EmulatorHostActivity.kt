package aenu.ax360e.compose

import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.documentfile.provider.DocumentFile
import aenu.ax360e.Application
import aenu.ax360e.Utils
import aenu.ax360e.compose.core.EmulatorRuntime
import aenu.ax360e.compose.core.EmulatorSession
import aenu.ax360e.compose.data.PreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import android.net.Uri
import android.content.Intent
import android.widget.Toast
import android.os.VibrationEffect
import android.os.Vibrator
import android.preference.PreferenceManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import aenu.ax360e.compose.gamepad.GamepadConfigDto
import aenu.ax360e.compose.gamepad.GamepadController
import aenu.ax360e.compose.gamepad.GamepadOverlay
import aenu.ax360e.compose.gamepad.Kc
import aenu.ax360e.compose.gamepad.rememberAutoHide

/**
 * The :emu emulator host (separate process; see manifest). Reads game_uri from the
 * Intent, performs the PRE-surface native setup, hosts a Vulkan SurfaceView, and drives
 * the EXACT surface->boot ordering (SP0 makes destroy/recreate safe). Hardware input
 * (controller + keyboard) only; the on-screen touch pad is SP4. onDestroy hard-kills
 * the process. Mirrors legacy aenu.ax360e.EmulatorActivity.
 */
class EmulatorHostActivity : ComponentActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "EmuHost"
        const val EXTRA_GAME_URI = "game_uri"   // matches GameLibraryViewModel.EXTRA_GAME_URI

        // Default Android-KeyEvent -> VirtualControl KEY_CODE map (KeyMapConfig defaults).
        // Mirrored locally: VirtualControl lives in :app, not on the :app-compose classpath.
        private const val KC_DPAD_LEFT = 0;  private const val KC_DPAD_UP = 1
        private const val KC_DPAD_RIGHT = 2; private const val KC_DPAD_DOWN = 3
        private const val KC_A = 4; private const val KC_B = 5
        private const val KC_X = 6; private const val KC_Y = 7
        private const val KC_BACK = 8; private const val KC_START = 9
        private const val KC_SHOULDER_L = 10; private const val KC_SHOULDER_R = 11
        private const val KC_TRIGGER_L = 14; private const val KC_TRIGGER_R = 15
        private const val KC_LTHUMB_LEFT = 16; private const val KC_LTHUMB_UP = 17
        private const val KC_LTHUMB_RIGHT = 18; private const val KC_LTHUMB_DOWN = 19
        private const val KC_RTHUMB_LEFT = 20; private const val KC_RTHUMB_UP = 21
        private const val KC_RTHUMB_RIGHT = 22; private const val KC_RTHUMB_DOWN = 23
        private const val KEY_VALUE_UNUSED = -1
    }

    private val session = EmulatorSession()
    private var surfaceView: SurfaceView? = null
    private var started = false          // surface-callback boot guard (mirrors legacy)

    // SP4 on-screen touch gamepad.
    private val gamepad by lazy { GamepadController(applicationContext) }
    private var hapticsEnabled = false
    private val bootedState = mutableStateOf(false)   // gates the overlay (post-boot only)

    // User hardware-key bindings (Android keycode -> game KEY_CODE), loaded from
    // KeymapStore in onCreate; falls back to GameButtons.DEFAULT_LOOKUP until loaded.
    @Volatile private var keyMap: Map<Int, Int> = aenu.ax360e.compose.data.GameButtons.DEFAULT_LOOKUP
    @Volatile private var vibrateEnabled: Boolean = false
    private var vibrator: android.os.Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fullscreen: legacy uses NoTitleBar.Fullscreen; keep the swapchain owning the
        // whole window. (Theme is also fullscreen; this is belt-and-braces immersive.)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )

        val gameUri = intent?.getStringExtra(EXTRA_GAME_URI)
        if (gameUri.isNullOrEmpty()) {
            Log.e(TAG, "No game_uri extra; finishing")
            finish(); return
        }
        if (!EmulatorRuntime.supportsVulkan) {
            Toast.makeText(this, "No Vulkan GPU; cannot boot", Toast.LENGTH_LONG).show()
            finish(); return
        }

        // PRE-surface native setup is async ONLY to (a) ensureLoaded() off-main on
        // delay-load devices and (b) read the persisted tree uri from DataStore. The
        // actual native setup_* calls are marshaled back to the main thread.
        lifecycleScope.launch {
            val store = aenu.ax360e.compose.data.KeymapStore(applicationContext)
            val (loadedMap, loadedVibrate, treeUri) = withContext(Dispatchers.IO) {
                EmulatorRuntime.ensureLoaded()            // idempotent; lazy on Adreno 5xx/6xx
                Triple(
                    store.androidToGameKey.firstOrNull()
                        ?: aenu.ax360e.compose.data.GameButtons.DEFAULT_LOOKUP,
                    store.vibrateEnabled.firstOrNull() ?: false,
                    readGameDirTreeUri(),
                )
            }
            keyMap = loadedMap
            vibrateEnabled = loadedVibrate
            if (treeUri == null) {
                Toast.makeText(this@EmulatorHostActivity,
                    "Game folder not set; open the library first", Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            // Re-take the persistable read grant on the tree uri (the core opens game
            // files through this Context's ContentResolver).
            runCatching {
                contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val tree = DocumentFile.fromTreeUri(this@EmulatorHostActivity, treeUri)
            if (tree == null) {
                Toast.makeText(this@EmulatorHostActivity,
                    "Game folder access lost", Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            prepareNative(gameUri, tree)                  // back on main (lifecycleScope = Main)
            installSurfaceView()
        }
    }

    /** Reads the SP1-B persisted tree uri (DataStore "ax360e_prefs", key "game_dir"). */
    private suspend fun readGameDirTreeUri(): Uri? {
        val s = PreferencesStore(applicationContext).gameDirUri.firstOrNull() ?: return null
        return Uri.parse(s)
    }

    /** The exact legacy PRE-surface order (EmulatorActivity.on_create:83-93). */
    private fun prepareNative(gameUri: String, tree: DocumentFile) {
        session.setupContext(this)
        session.setupDocumentFileTree(tree)
        session.setupGamePath(gameUri)
        session.setupLaunchArgs(arrayOf(
            "--storage_root=" + Utils.get_storage_root_path(),
            "--config=" + Application.get_global_config_file().absolutePath,
            "--log_file=" + Utils.get_log_file_path(),
        ))
        session.setupUriInfoListFile(Application.get_uri_info_list_file().absolutePath)
    }

    private fun installSurfaceView() {
        val sv = SurfaceView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            holder.addCallback(this@EmulatorHostActivity)
            setOnGenericMotionListener { _, ev -> onGenericMotion(ev) }
        }
        surfaceView = sv
        val compose = ComposeView(this).apply {
            setContent {
                val cfg by gamepad.config.collectAsState(initial = GamepadConfigDto())
                val landscape = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                // Recompose-trigger for boot gate: poll booted via a state hoisted in the host.
                val booted by bootedState
                val controls = remember(cfg, landscape) { gamepad.controlsFor(cfg, landscape) }
                val (visible, poke) = rememberAutoHide(cfg.globals.autoHideSeconds)
                val alpha by animateFloatAsState(
                    if (visible) cfg.globals.opacity else 0f, tween(500), label = "padAlpha")

                // Apply haptics pref each composition (cheap; reads cached field).
                LaunchedEffect(cfg.globals.hapticsEnabled) {
                    configureHaptics(cfg.globals.hapticsEnabled)
                }
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { sv }, modifier = Modifier.fillMaxSize())
                    // Stay MOUNTED whenever enabled (alpha drives only the DRAW): the
                    // pointerInput must keep receiving touches so the auto-hide wake tap
                    // fires, and so a held control is never unmounted mid-press (stuck).
                    if (booted && cfg.globals.enabled) {
                        GamepadOverlay(
                            controls = controls,
                            opacity = alpha,
                            onUserInteraction = poke,
                            onKeyEvent = { kc, pressed, v ->
                                if (pressed && v == Kc.VALUE_UNUSED) maybeVibrate()
                                session.keyEvent(kc, pressed, v)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        setContentView(compose)
        sv.requestFocus()
    }

    /** Honor the SAME legacy enable_vibrator pref AND the new globals flag (new wins when
     *  set, else fall back to legacy so existing users keep their setting). */
    private fun configureHaptics(globalsFlag: Boolean) {
        val legacy = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("enable_vibrator", false)
        hapticsEnabled = globalsFlag || legacy
        if (hapticsEnabled && vibrator == null)
            vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
    }
    private fun maybeVibrate() {
        if (!hapticsEnabled) return
        vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---- SurfaceHolder.Callback: the load-bearing surface->boot ordering ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!started) {
            started = true
            session.attachSurface(holder.surface)
            try {
                session.bootOnce()
                bootedState.value = true                  // reveal the SP4 overlay post-boot
            } catch (t: RuntimeException) {
                Log.e(TAG, "boot failed", t)
                finish()                                  // fatal; single-shot core
            }
        } else {
            // Post-rotation/background re-create: re-attach, resume; NEVER boot again.
            session.attachSurface(holder.surface)
            session.resumeIfPaused()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!started) return
        if (width == 0 || height == 0) return
        session.changeSurface(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!started) return
        session.detachSurface()                           // synchronous GPU drain (SP0)
    }

    // ---- Lifecycle teardown ----

    override fun onPause() {
        super.onPause()
        session.flushGpuCaches()                          // best-effort; never throws
    }

    override fun onDestroy() {
        super.onDestroy()
        System.exit(0)                                    // hard-kill :emu (single-shot core)
    }

    // ---- Hardware input -> session.keyEvent ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val gameKey = keyMap[keyCode] ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            session.keyEvent(gameKey, true, KEY_VALUE_UNUSED)
            vibrate()
            return true
        }
        return super.onKeyDown(keyCode, event)            // ignore auto-repeats
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val gameKey = keyMap[keyCode] ?: return super.onKeyUp(keyCode, event)
        session.keyEvent(gameKey, false, KEY_VALUE_UNUSED)
        return true
    }

    /** Joystick axes + hat (D-pad). Mirrors EmulatorActivity.onGenericMotion/handle_dpad. */
    private fun onGenericMotion(event: MotionEvent): Boolean {
        if (isNonDpadSource(event) && handleHat(event)) return true
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return super.onGenericMotionEvent(event)
        }
        // Left stick
        emitAxisPair(event.getAxisValue(MotionEvent.AXIS_X),
            negKey = KC_LTHUMB_LEFT, posKey = KC_LTHUMB_RIGHT, invert = false)
        emitAxisPair(event.getAxisValue(MotionEvent.AXIS_Y),
            negKey = KC_LTHUMB_UP, posKey = KC_LTHUMB_DOWN, invert = true)   // Y inverted
        // Right stick (Z / RZ)
        emitAxisPair(event.getAxisValue(MotionEvent.AXIS_Z),
            negKey = KC_RTHUMB_LEFT, posKey = KC_RTHUMB_RIGHT, invert = false)
        emitAxisPair(event.getAxisValue(MotionEvent.AXIS_RZ),
            negKey = KC_RTHUMB_UP, posKey = KC_RTHUMB_DOWN, invert = true)
        return true
    }

    /** For one axis emit the opposing thumb directions, scaled to signed-short range.
     *  negative -> negKey pressed with |v|, posKey released; positive -> posKey; 0 -> both released.
     *  invert flips sign first (screen Y is up-negative; X360 up is positive). */
    private fun emitAxisPair(axis: Float, negKey: Int, posKey: Int, invert: Boolean) {
        val v = if (invert) -axis else axis
        when {
            v < 0f -> {
                session.keyEvent(posKey, false, 0)
                session.keyEvent(negKey, true, (v * 32768f).toInt())          // negative magnitude
            }
            v > 0f -> {
                session.keyEvent(negKey, false, 0)
                session.keyEvent(posKey, true, (v * 32767f).toInt())
            }
            else -> {
                session.keyEvent(negKey, false, 0)
                session.keyEvent(posKey, false, 0)
            }
        }
    }

    /** Hat axes -> D-pad. Returns true if any direction is pressed. */
    private fun handleHat(event: MotionEvent): Boolean {
        var pressed = false
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        when {
            hx == -1f -> { dpad(KC_DPAD_LEFT, KC_DPAD_RIGHT); pressed = true }
            hx == 1f  -> { dpad(KC_DPAD_RIGHT, KC_DPAD_LEFT); pressed = true }
        }
        when {
            hy == -1f -> { dpad(KC_DPAD_UP, KC_DPAD_DOWN); pressed = true }
            hy == 1f  -> { dpad(KC_DPAD_DOWN, KC_DPAD_UP); pressed = true }
        }
        if (pressed) return true
        // No hat direction -> release all four (legacy behavior).
        session.keyEvent(KC_DPAD_LEFT, false, KEY_VALUE_UNUSED)
        session.keyEvent(KC_DPAD_UP, false, KEY_VALUE_UNUSED)
        session.keyEvent(KC_DPAD_RIGHT, false, KEY_VALUE_UNUSED)
        session.keyEvent(KC_DPAD_DOWN, false, KEY_VALUE_UNUSED)
        return false
    }

    private fun dpad(pressCode: Int, releaseCode: Int) {
        session.keyEvent(pressCode, true, KEY_VALUE_UNUSED)
        session.keyEvent(releaseCode, false, KEY_VALUE_UNUSED)
    }

    private fun vibrate() {
        if (!vibrateEnabled) return
        val v = vibrator ?: (getSystemService(android.content.Context.VIBRATOR_SERVICE)
            as? android.os.Vibrator)?.also { vibrator = it } ?: return
        @Suppress("DEPRECATION")
        v.vibrate(
            android.os.VibrationEffect.createOneShot(25L,
                android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    /** Legacy isDpadDevice: TRUE when the device is NOT a SOURCE_DPAD (i.e. treat as
     *  joystick/hat). Name kept descriptive here. */
    private fun isNonDpadSource(event: MotionEvent): Boolean =
        event.source and InputDevice.SOURCE_DPAD != InputDevice.SOURCE_DPAD
}
