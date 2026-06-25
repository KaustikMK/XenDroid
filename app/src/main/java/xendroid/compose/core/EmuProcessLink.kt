package xendroid.compose.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import androidx.core.content.getSystemService

/**
 * Ties the single-shot :emu render process's lifetime to the MAIN process.
 *
 * :emu is a separate OS process forked from the zygote, so it does NOT die when the
 * main process is closed, crashes, or is OOM-killed (Android never kills sibling
 * processes for you). We hand :emu a Binder owned by the main process; :emu
 * linkToDeath's it and hard-kills itself the instant the main process goes away.
 * We also kill any stale :emu before a fresh launch, so a wedged/orphaned core can
 * never block the next single-shot boot.
 */
object EmuProcessLink {
    private const val EXTRA_ALIVE_BUNDLE = "xendroid.alive_bundle"
    private const val KEY_MAIN_ALIVE = "main_alive"

    /** Owned by the MAIN process (only attachMainAliveToken, called from the library,
     *  ever reads it). The proxy handed to :emu therefore tracks the main process. */
    private val mainAliveToken: IBinder = Binder()

    /** Held alive for the :emu process lifetime so the death link is not GC'd. */
    private val selfKill = object : IBinder.DeathRecipient {
        override fun binderDied() = Process.killProcess(Process.myPid())
    }

    /** MAIN process: attach the liveness token to a live launch Intent. NOT for the
     *  pinned-shortcut Intent (a Binder cannot be persisted). */
    fun attachMainAliveToken(intent: Intent) {
        intent.putExtra(EXTRA_ALIVE_BUNDLE, Bundle().apply {
            putBinder(KEY_MAIN_ALIVE, mainAliveToken)
        })
    }

    /** :emu process: self-kill when the main process (the token's owner) dies. Call
     *  once from EmulatorHostActivity.onCreate. No-op if no token (e.g. shortcut launch). */
    fun bindToMainProcessDeath(intent: Intent) {
        val token = intent.getBundleExtra(EXTRA_ALIVE_BUNDLE)?.getBinder(KEY_MAIN_ALIVE)
            ?: return
        runCatching { token.linkToDeath(selfKill, 0) }
            .onFailure { Process.killProcess(Process.myPid()) }  // main already gone
    }

    /** MAIN process: kill any leftover :emu before launching, so a wedged/orphaned
     *  single-shot core never blocks the next boot. */
    fun killStaleEmu(context: Context) {
        val am = context.getSystemService<ActivityManager>() ?: return
        val mine = Process.myPid()
        runCatching {
            am.runningAppProcesses.orEmpty()
                .filter { it.processName.endsWith(":emu") && it.pid != mine }
                .forEach { Process.killProcess(it.pid) }
        }
    }
}
