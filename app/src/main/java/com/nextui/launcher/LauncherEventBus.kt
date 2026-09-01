package com.nextui.launcher

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * LauncherEventBus — unified, non-blocking event stream for system triggers.
 *
 * ── Purpose ─────────────────────────────────────────────────────────────────
 * Decouples system-level producers (package broadcast receiver, Home-button
 * presses, memory pressure) from consumers (ViewModel refresh pipeline,
 * icon cache) so that:
 *
 *  • Broadcast receivers never touch UI or suspend — they post and return.
 *  • No receiver is ever registered against an Activity/Composition scope,
 *    eliminating a whole class of leaks and redundant full-state refreshes.
 *  • Consumers can conflate/debounce bursts (e.g. batch app updates firing
 *    dozens of PACKAGE_REPLACED events in a second) in one place.
 *
 * ── Delivery guarantees ─────────────────────────────────────────────────────
 * Events are delivered atomically to all subscribers. The buffer drops the
 * *oldest* event under pressure; [Event.PackagesChanged] and friends are
 * idempotent state-sync triggers, so dropping intermediates is always safe.
 */
object LauncherEventBus {

    sealed interface Event {
        /**
         * An app was installed / removed / updated.
         * [packageName] is the affected package; consumers should treat this
         * as a hint and re-sync rather than trust incremental accuracy.
         */
        data class PackagesChanged(val packageName: String?) : Event

        /** Home button pressed while the launcher was already on top. */
        data object HomePressed : Event

        /** System asked us to trim memory; carries ComponentCallbacks2 level. */
        data class TrimMemory(val level: Int) : Event
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * Non-blocking publish. Safe to call from any thread, including
     * BroadcastReceiver.onReceive on the main thread.
     */
    fun post(event: Event) {
        _events.tryEmit(event)
    }
}
