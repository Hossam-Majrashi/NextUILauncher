package com.nextui.launcher

/**
 * Lifecycle phases tracked by [DiagnosticsLogger] telemetry.
 *
 * Each phase marks a point in time where jank or latency may be introduced;
 * recording them allows the diagnostics overlay/dialog to attribute delays
 * to a specific subsystem instead of a generic "the app felt slow".
 */
enum class LagPhase {
    /** Application process start (cold boot). */
    BOOT,

    /** First interactive frame delivered to the user. */
    UI_READY,

    /** Initial app-list sync from PackageManager finished. */
    DATA_LOADED,

    /** External activity launch (tap on an app icon). */
    LAUNCH,

    /** Icon pipeline event: L1 RAM hit / L2 disk hit / L3 PackageManager decode. */
    ICON_CACHE,

    /** Icon pre-warm / predictive prefetch batch. */
    ICON_PRELOAD,

    /** Search filtering pass executed on the background pipeline. */
    SEARCH,

    /** Full or incremental app-list refresh (package broadcast, manual). */
    REFRESH,

    /** Memory trim event delivered by the system (onTrimMemory/onLowMemory). */
    MEMORY_TRIM
}
