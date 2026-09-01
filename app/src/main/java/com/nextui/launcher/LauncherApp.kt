package com.nextui.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.nextui.launcher.data.IconCache

/**
 * LauncherApp — application lifecycle owner.
 *
 * Responsibilities:
 *  1. Initialize the icon pipeline ([IconCache.init]).
 *  2. Own the SINGLE app-scoped package-change receiver. It does nothing but
 *     post to [LauncherEventBus] — no UI coupling, no main-thread work, no
 *     per-Composition receiver registration (which previously leaked and
 *     forced redundant full-state refreshes).
 *  3. Forward system memory pressure to the icon cache so L1 RAM sheds
 *     progressively while the instant disk cache is preserved.
 */
class LauncherApp : Application() {

    // ── Package broadcast pipeline (Agent 1: zero main-thread blocking) ──────

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            when (intent.action) {
                // A replaced package fires ADDED + REPLACED; a remove during
                // update carries EXTRA_REPLACING — collapse all of it into a
                // single semantic event and let consumers debounce.
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    DiagnosticsLogger.increment(DiagnosticsLogger.Metrics.PACKAGE_EVENT_COUNT)
                    LauncherEventBus.post(LauncherEventBus.Event.PackagesChanged(pkg))
                }

                Intent.ACTION_PACKAGE_REMOVED -> {
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    DiagnosticsLogger.increment(DiagnosticsLogger.Metrics.PACKAGE_EVENT_COUNT)
                    LauncherEventBus.post(LauncherEventBus.Event.PackagesChanged(pkg))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLogger.recordPhase(LagPhase.BOOT, "Application.onCreate")
        IconCache.init(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // System package broadcasts are protected; no exported flag needed.
        registerReceiver(packageReceiver, filter)
    }

    // ── Memory pressure hooks (Agent 2: dynamic L1 shedding) ─────────────────

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Synchronous shed — under real memory pressure we must release now,
        // not whenever a coroutine gets scheduled.
        IconCache.trimMemory(level)
        LauncherEventBus.post(LauncherEventBus.Event.TrimMemory(level))
    }

    override fun onLowMemory() {
        super.onLowMemory()
        IconCache.trimMemory()
    }
}
