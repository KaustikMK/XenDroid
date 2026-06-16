package aenu.ax360e.compose.core

import android.content.Context
import android.view.Surface
import androidx.documentfile.provider.DocumentFile
import aenu.emulator.Emulator as BaseEmulator

/**
 * Thin, UI-thread-affine facade over the native xenia singleton (aenu.ax360e.Emulator
 * via EmulatorRuntime). Mirrors the EXACT legacy EmulatorActivity ordering and owns the
 * boot-once invariant. One instance per EmulatorHostActivity (one process / one core).
 *
 * Contract (caller MUST keep order; native enforces nothing):
 *   prepare() -> {setupContext, setupDocumentFileTree, setupGamePath, setupLaunchArgs,
 *                 setupUriInfoListFile}                           [PRE-surface]
 *   attachSurface(s) -> bootOnce()                                [first surface only]
 *   changeSurface(w,h)                                            [surfaceChanged]
 *   attachSurface(s) (+resumeIfPaused) on later surfaceCreated    [NEVER boot again]
 *   detachSurface()                                               [surfaceDestroyed]
 *   flushGpuCaches()                                              [onPause, best-effort]
 *   keyEvent(...)                                                 [hardware input]
 */
class EmulatorSession {

    /** Set true the first time bootOnce() runs; gates the once-only boot. */
    var booted: Boolean = false
        private set

    /** Non-null only after ensureLoaded(); ::core throws clearly if used too early. */
    private val core: aenu.ax360e.Emulator
        get() = EmulatorRuntime.emulator
            ?: error("EmulatorSession used before EmulatorRuntime.ensureLoaded()")

    // ---- PRE-surface setup (all on the main thread, in this exact order) ----

    fun setupContext(ctx: Context) = core.setup_context(ctx)

    fun setupDocumentFileTree(tree: DocumentFile) = core.setup_document_file_tree(tree)

    /** Per-game launch uri; fd = -1 so native opens the SAF descriptor itself. */
    fun setupGamePath(gameUri: String) =
        core.setup_game_path(BaseEmulator.Path.from(gameUri, -1))

    fun setupLaunchArgs(args: Array<String>) = core.setup_launch_args(args)

    fun setupUriInfoListFile(path: String) = core.setup_uri_info_list_file(path)

    // ---- Surface lifecycle ----

    /** Stash/attach the render surface. PRE-boot it just stashes; POST-boot it
     *  async-recreates the swapchain. Pass null to detach (synchronous GPU drain). */
    fun attachSurface(surface: Surface?) = core.setup_surface(surface)

    fun detachSurface() = core.setup_surface(null)

    fun changeSurface(width: Int, height: Int) = core.change_surface(width, height)

    /** Boot the core EXACTLY ONCE. No-op (and logs nothing) on later calls. MUST be
     *  preceded by a non-null attachSurface(). Translates the checked BootException. */
    @Throws(RuntimeException::class)
    fun bootOnce() {
        if (booted) return
        booted = true
        try {
            core.boot()
        } catch (e: BaseEmulator.BootException) {
            throw RuntimeException("xenia boot failed", e)
        }
    }

    fun resumeIfPaused() {
        if (core.is_paused()) core.resume()
    }

    fun pause() = core.pause()
    fun resume() = core.resume()

    /** Best-effort GPU cache flush (onPause). Swallows everything; never throws. */
    fun flushGpuCaches() {
        if (!booted) return
        runCatching { EmulatorRuntime.emulator?.flush_gpu_caches() }
    }

    /** Single input sink for ALL controls. value = KEY_VALUE_UNUSED (-1) for digital,
     *  or a signed short magnitude for thumbsticks.
     *  Dropped until boot() has run: native ae::key_event dereferences
     *  g_windowed_app_ref unconditionally, which is null until the detached boot
     *  thread populates it (a controller pressed during the boot splash would crash). */
    fun keyEvent(keyCode: Int, pressed: Boolean, value: Int) {
        if (!booted) return
        core.key_event(keyCode, pressed, value)
    }

    // ---- Debug stats (UI-thread polled; reads native lock-free atomics) ----

    /** Last presented guest-frame interval in ms (0 before first present / after pause). */
    fun lastFrameTimeMs(): Double = if (booted) core.last_frame_time_ms() else 0.0

    /** Instant fps = 1000/last-frame-ms (0 when no frame timed yet). NOT the average. */
    fun instantFps(): Double = if (booted) core.instant_fps() else 0.0
}
