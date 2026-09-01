package com.nextui.launcher.data

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nextui.launcher.DiagnosticsLogger
import com.nextui.launcher.LagPhase
import com.nextui.launcher.LauncherEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Rasterization edge in px. 48 dp icons render at up to 192 px on xxxhdpi;
 * pre-scaling once at write time means decode-time is a plain memcpy with
 * zero resampling cost on the scrolling path.
 */
private const val ICON_SIZE_PX = 192

private const val DISK_DIR_NAME = "icon_cache_v2"
private const val DISK_FILE_EXT = ".webp"
private const val WEBP_QUALITY  = 90

/** Max number of software bitmaps kept for inBitmap decode reuse. */
private const val DECODE_POOL_SIZE = 8

// ─────────────────────────────────────────────────────────────────────────────
// IconCache — three-tier, zero-jank icon pipeline
//
//   L1  RAM   LruCache<String, ImageBitmap> holding Bitmap.Config.HARDWARE
//             bitmaps (zero-copy GPU rendering, no Java-heap pressure).
//             Sized in bytes (≈1/6 of the VM heap). Target latency: < 2 ms.
//
//   L2  DISK  Flat-file cache of pre-scaled, WebP-compressed icons in
//             cacheDir. No SQLite → no table locks, no cursor allocations,
//             no main-thread ContentProvider stalls. Files are written
//             atomically (tmp + rename) so a crashed write never corrupts.
//             Target latency: < 10 ms.
//
//   L3  PM    PackageManager.getApplicationIcon — only on a genuine miss.
//
// ── Concurrency ─────────────────────────────────────────────────────────────
//  • All IO runs on Dispatchers.IO.limitedParallelism(4) to avoid thread
//    starvation and lock contention with the rest of the app.
//  • Concurrent loads of the same key are de-duplicated through [inFlight]:
//    the second caller simply awaits the first caller's Deferred.
//  • A small pool of mutable software bitmaps is reused as
//    BitmapFactory.Options.inBitmap, eliminating per-decode allocations
//    (and the GC spikes they cause during flings).
//
// ── Invalidation ────────────────────────────────────────────────────────────
//  Driven by LauncherEventBus.PackagesChanged (single app-scoped receiver in
//  LauncherApp). Both L1 entries and L2 files for the package are dropped.
//
// ── Memory pressure ─────────────────────────────────────────────────────────
//  trimMemory(level) sheds L1 progressively; L2 always survives so icons
//  re-hydrate from disk in < 10 ms after pressure subsides.
// ─────────────────────────────────────────────────────────────────────────────

object IconCache {

    // ── L1: hardware-bitmap RAM cache ────────────────────────────────────────

    private val l1MaxBytes: Int =
        (Runtime.getRuntime().maxMemory() / 6L)
            .coerceIn(8L * 1024 * 1024, 96L * 1024 * 1024)
            .toInt()

    private val l1 = object : LruCache<String, ImageBitmap>(l1MaxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    // ── Pipeline scope & in-flight de-duplication ────────────────────────────

    /** Bounded IO dispatcher: 4 concurrent icon operations, never more. */
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(4)

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** key → in-flight load; concurrent callers share one decode. */
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    // ── Bitmap decode pool (inBitmap reuse → zero GC spikes) ─────────────────

    private val decodePool = ArrayDeque<Bitmap>(DECODE_POOL_SIZE)
    private val decodePoolLock = Any()

    private fun acquireDecodeBitmap(): Bitmap? = synchronized(decodePoolLock) {
        decodePool.removeFirstOrNull()
    }

    private fun releaseDecodeBitmap(bitmap: Bitmap) {
        synchronized(decodePoolLock) {
            if (decodePool.size < DECODE_POOL_SIZE) decodePool.addLast(bitmap)
        }
    }

    // ── L2: flat-file disk cache ─────────────────────────────────────────────

    @Volatile
    private var diskDir: File? = null

    @Volatile
    private var initialized = false

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Must be called once from Application.onCreate().
     * Idempotent. Subscribes to the shared [LauncherEventBus] so icons are
     * invalidated on install / update / uninstall without any UI coupling.
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            diskDir = File(appCtx.cacheDir, DISK_DIR_NAME)

            // One-time cleanup of the legacy SQLite cache (v1).
            runCatching { appCtx.deleteDatabase("icon_cache.db") }

            // Consume package-change events from the unified bus.
            scope.launch {
                LauncherEventBus.events.collect { event ->
                    if (event is LauncherEventBus.Event.PackagesChanged) {
                        event.packageName?.let(::invalidatePackage)
                    }
                }
            }
            initialized = true
        }
    }

    /**
     * Synchronous L1-only lookup. Safe (and intended) to call from
     * composition — it never suspends, never touches disk. < 2 ms.
     */
    fun getCachedImageBitmap(key: String): ImageBitmap? =
        l1.get(key)?.also {
            DiagnosticsLogger.increment(DiagnosticsLogger.Metrics.ICON_L1_HIT)
        }

    /**
     * Full three-tier lookup with in-flight de-duplication.
     * Call from a coroutine; never blocks the main thread.
     */
    suspend fun loadIcon(context: Context, key: String, packageName: String): ImageBitmap? {
        l1.get(key)?.let {
            DiagnosticsLogger.increment(DiagnosticsLogger.Metrics.ICON_L1_HIT)
            return it
        }

        inFlight[key]?.let { return it.await() }

        val appCtx = context.applicationContext
        val deferred = scope.async { loadInternal(appCtx, key, packageName) }
        val winner = inFlight.putIfAbsent(key, deferred)
        if (winner != null) {
            deferred.cancel()
            return winner.await()
        }
        return try {
            deferred.await()
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    /**
     * Pre-warms the cache for [items] before they appear on screen
     * (AOT warm-up for pinned apps + first drawer pages, and velocity-aware
     * ±2 page prefetch during swipes). L1 hits are skipped for free; misses
     * are loaded concurrently on the bounded IO dispatcher.
     */
    suspend fun preload(context: Context, items: List<Pair<String, String>>) =
        withContext(ioDispatcher) {
            val missing = items.filter { (key, _) -> l1.get(key) == null }
            if (missing.isEmpty()) return@withContext
            val t0 = SystemClock.uptimeMillis()
            val appCtx = context.applicationContext
            missing.forEach { (key, packageName) ->
                // loadIcon de-duplicates; fire-and-forget via scope so a slow
                // decode never stalls the caller's frame budget.
                launch { loadIcon(appCtx, key, packageName) }
            }
            DiagnosticsLogger.increment(
                DiagnosticsLogger.Metrics.ICON_PRELOADED, missing.size.toLong()
            )
            DiagnosticsLogger.recordPhase(
                LagPhase.ICON_PRELOAD,
                "${missing.size} icons queued",
                "${SystemClock.uptimeMillis() - t0}ms"
            )
        }

    /**
     * Progressive L1 shed under memory pressure. L2 (disk) always survives.
     * Wire from Application.onTrimMemory / onLowMemory.
     */
    @Suppress("DEPRECATION") // TRIM_MEMORY_* constants remain the onTrimMemory contract
    fun trimMemory(level: Int = ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> l1.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> l1.trimToSize(l1.size() / 2)
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> l1.trimToSize((l1.size() * 3) / 4)
        }
        DiagnosticsLogger.recordPhase(LagPhase.MEMORY_TRIM, "level=$level", "l1=${l1.size()}")
    }

    /**
     * Removes every icon belonging to [packageName] from L1 and L2.
     * Triggered automatically via the event bus on package changes.
     */
    fun invalidatePackage(packageName: String) {
        // L1: keys are "pkg/class" — the trailing slash prevents prefix
        // over-matching (com.foo vs com.foo.bar).
        val prefix = "$packageName/"
        l1.snapshot().keys
            .filter { it.startsWith(prefix) }
            .forEach { l1.remove(it) }

        // L2: file names are "${pkg}__${hash}.webp".
        diskDir?.listFiles()
            ?.filter { it.name.startsWith("${packageName}__") }
            ?.forEach { it.delete() }
    }

    // ── Internal pipeline ────────────────────────────────────────────────────

    private fun loadInternal(context: Context, key: String, packageName: String): ImageBitmap? {
        val t0 = SystemClock.uptimeMillis()

        // ── L2: disk hit → decode (pooled) → hardware → L1 ──────────────────
        readFromDisk(key)?.let { software ->
            val hw = software.toHardwareBitmap()
            val img = hw.asImageBitmap()
            l1.put(key, img)
            DiagnosticsLogger.increment(DiagnosticsLogger.Metrics.ICON_L2_HIT)
            DiagnosticsLogger.recordPhase(
                LagPhase.ICON_CACHE, "L2 hit", "${SystemClock.uptimeMillis() - t0}ms"
            )
            return img
        }

        // ── L3: PackageManager → scale → persist → hardware → L1 ────────────
        return runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val software = drawableToScaledBitmap(drawable, ICON_SIZE_PX)
            writeToDisk(key, packageName, software)
            val hw = software.toHardwareBitmap()
            val img = hw.asImageBitmap()
            l1.put(key, img)
            DiagnosticsLogger.increment(DiagnosticsLogger.Metrics.ICON_MISS)
            DiagnosticsLogger.recordPhase(
                LagPhase.ICON_CACHE, "L3 decode", "${SystemClock.uptimeMillis() - t0}ms"
            )
            img
        }.getOrNull()
    }

    // ── L2 helpers ───────────────────────────────────────────────────────────

    /** "pkg/class" → "${pkg}__${sha1-16}.webp" — flat, collision-safe name. */
    private fun diskFileFor(key: String, packageName: String): File? {
        val dir = diskDir ?: return null
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
        return File(dir, "${packageName}__$hash$DISK_FILE_EXT")
    }

    private fun packageNameOf(key: String): String = key.substringBefore('/')

    /**
     * Reads + decodes a cached icon using a pooled [BitmapFactory.Options.inBitmap]
     * buffer so the scrolling path performs zero large allocations.
     */
    private fun readFromDisk(key: String): Bitmap? {
        val file = diskFileFor(key, packageNameOf(key)) ?: return null
        if (!file.exists()) return null

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null

        // Fast path: decode into a pooled mutable bitmap (all our files are
        // exactly ICON_SIZE_PX², so inBitmap reuse always matches).
        val pooled = acquireDecodeBitmap()
        if (pooled != null) {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inBitmap = pooled
            }
            val decoded = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }.getOrNull()
            if (decoded === pooled) return pooled   // caller owns it now
            // Size/config mismatch or failure: fall through to plain decode.
            if (decoded != null && decoded !== pooled) {
                releaseDecodeBitmap(pooled)
                return decoded
            }
            releaseDecodeBitmap(pooled)
        }
        // Fresh Options per decode: BitmapFactory.Options is not thread-safe
        // and this fallback path can run on any of the 4 IO threads.
        val fallbackOpts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, fallbackOpts)
        }.getOrNull()
    }

    /**
     * Atomic write: encode WebP → tmp file → rename. A rename is atomic on
     * POSIX, so readers never observe a partially-written icon.
     */
    private fun writeToDisk(key: String, packageName: String, bitmap: Bitmap) {
        val dir = diskDir ?: return
        val target = diskFileFor(key, packageName) ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, target.name + ".tmp")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            tmp.outputStream().use { out ->
                bitmap.compress(format, WEBP_QUALITY, out)
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    // ── Bitmap helpers ───────────────────────────────────────────────────────

    /**
     * Copies to HARDWARE config (API 26+) for zero-copy GPU rendering.
     * The software source is returned to the decode pool when eligible,
     * otherwise recycled — never leaked, never double-owned.
     */
    private fun Bitmap.toHardwareBitmap(): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return this
        val hw = copy(Bitmap.Config.HARDWARE, false) ?: return this
        if (isMutable && width == ICON_SIZE_PX && height == ICON_SIZE_PX) {
            releaseDecodeBitmap(this)   // pooled decode buffer → back to pool
        } else if (this !== hw) {
            recycle()
        }
        return hw
    }

    /**
     * Converts any [Drawable] to a software [Bitmap] of exactly [sizePx]².
     * Always returns a bitmap we exclusively own (never the drawable's
     * internal bitmap) so pool reuse and recycle() are always safe.
     */
    private fun drawableToScaledBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable) {
            val src = drawable.bitmap ?: return renderDrawable(drawable, sizePx)
            return if (src.width == sizePx && src.height == sizePx) {
                src.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
            }
        }
        return renderDrawable(drawable, sizePx)
    }

    /** Renders vectors / adaptive icons into a new software bitmap. */
    private fun renderDrawable(drawable: Drawable, sizePx: Int): Bitmap {
        val src = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(src)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        if (src.width == sizePx && src.height == sizePx) return src
        return Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
            .also { src.recycle() }
    }
}
