package com.nextui.launcher

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * DiagnosticsLogger — lightweight, non-blocking telemetry stream.
 *
 * ── Design ──────────────────────────────────────────────────────────────────
 * • Every event is a single immutable [Entry] appended to a bounded in-memory
 *   ring buffer (O(1), no locks on the hot path beyond a short synchronized
 *   block) and offered to a [SharedFlow] with
 *   [kotlinx.coroutines.flow.BufferOverflow.DROP_OLDEST] so a slow/absent
 *   subscriber can never back-pressure the caller.
 * • Metric counters (icon cache hit/miss rates, phase counters, …) live in
 *   [AtomicLong]s keyed by name — allocation-free increments safe from any
 *   thread, including IO dispatcher threads inside the icon pipeline.
 * • Nothing here ever suspends or blocks: every public function is a plain
 *   synchronous call that finishes in nanoseconds, making it safe to call
 *   from composition, layout, or frame callbacks.
 *
 * ── Streamed telemetry ──────────────────────────────────────────────────────
 * Subscribers (debug overlays, tests, profilers) can collect [events] for a
 * real-time feed, or poll [metricSnapshot] for hit-rate style gauges.
 */
object DiagnosticsLogger {

    // ── Event model ─────────────────────────────────────────────────────────

    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestampMs: Long,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        override fun toString(): String =
            "${timeFmt.format(Date(timestampMs))}  ${level.name.first()}/$tag: $message"

        private companion object {
            val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }

    // ── Ring buffer (recent history for the diagnostics dialog) ─────────────

    private const val MAX_ENTRIES = 300
    private val ring = ArrayDeque<Entry>(MAX_ENTRIES)
    private val ringLock = Any()

    // ── Non-blocking telemetry stream ───────────────────────────────────────

    private val _events = MutableSharedFlow<Entry>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Real-time telemetry stream. Never back-pressures producers. */
    val events: SharedFlow<Entry> = _events.asSharedFlow()

    // ── Metric counters (hit/miss rates, phase counters, timings) ───────────

    /** Well-known metric keys used across the app. */
    object Metrics {
        const val ICON_L1_HIT = "icon.l1.hit"
        const val ICON_L2_HIT = "icon.l2.hit"
        const val ICON_MISS = "icon.miss"
        const val ICON_PRELOADED = "icon.preloaded"
        const val REFRESH_COUNT = "refresh.count"
        const val PACKAGE_EVENT_COUNT = "package.event.count"
        const val SEARCH_RUN_COUNT = "search.run.count"
    }

    private val counters = ConcurrentHashMap<String, AtomicLong>()

    /** Atomically increments a metric counter. Allocation-free after warmup. */
    fun increment(metric: String, delta: Long = 1L) {
        counters.getOrPut(metric) { AtomicLong(0L) }.addAndGet(delta)
    }

    /** Point-in-time snapshot of every counter (for diagnostics UI). */
    fun metricSnapshot(): Map<String, Long> = counters.mapValues { it.value.get() }

    /** Resets all metric counters (e.g. before a profiling run). */
    fun resetMetrics() = counters.clear()

    // ── Public logging API (kept source-compatible with the old logger) ─────

    fun log(message: String) {
        emit(Level.DEBUG, "App", message)
    }

    fun recordError(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val suffix = throwable?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""
        emit(Level.ERROR, tag, message + suffix)
    }

    fun recordPhase(phase: LagPhase, message: String? = null, extra: Any? = null) {
        increment("phase.${phase.name.lowercase(Locale.US)}")
        val text = buildString {
            append(phase.name)
            if (!message.isNullOrEmpty()) append(" • ").append(message)
            if (extra != null) append(" • ").append(extra)
        }
        if (phase == LagPhase.LAUNCH || phase == LagPhase.REFRESH) Log.i("LagPhase", text)
        emit(Level.INFO, "Phase", text)
    }

    fun recordWarn(tag: String, message: String) = emit(Level.WARN, tag, message)

    // ── Ring buffer access ──────────────────────────────────────────────────

    /** Snapshot of the most recent entries, oldest first. */
    fun recentEntries(): List<Entry> = synchronized(ringLock) { ring.toList() }

    /**
     * Human-readable log dump used by the diagnostics dialog.
     * Includes the metric snapshot so cache hit-rates are visible at a glance.
     */
    fun getLog(): String {
        val entries = recentEntries()
        if (entries.isEmpty() && counters.isEmpty()) return ""
        return buildString {
            if (counters.isNotEmpty()) {
                appendLine("── Metrics ──")
                metricSnapshot().toSortedMap().forEach { (k, v) -> appendLine("$k = $v") }
                appendLine()
            }
            appendLine("── Events ──")
            entries.forEach { appendLine(it.toString()) }
        }.trim()
    }

    fun clear() {
        synchronized(ringLock) { ring.clear() }
        counters.clear()
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun emit(level: Level, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(ringLock) {
            if (ring.size >= MAX_ENTRIES) ring.removeFirst()
            ring.addLast(entry)
        }
        // tryEmit is non-blocking; with DROP_OLDEST the stream can never stall.
        _events.tryEmit(entry)
    }
}
