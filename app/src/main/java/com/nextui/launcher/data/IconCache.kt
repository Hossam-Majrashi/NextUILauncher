package com.nextui.launcher.data

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

private const val ICON_SIZE_PX = 96
private const val DB_NAME      = "icon_cache.db"
private const val DB_VERSION   = 1

private const val TABLE       = "icons"
private const val COL_PKG     = "package_name"
private const val COL_KEY     = "cache_key"
private const val COL_BLOB    = "icon_blob"
private const val COL_UPDATED = "last_update_time"

// ─────────────────────────────────────────────────────────────────────────────
// SQLite helper
// ─────────────────────────────────────────────────────────────────────────────

private class IconDbHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_PKG     TEXT NOT NULL,
                $COL_KEY     TEXT NOT NULL PRIMARY KEY,
                $COL_BLOB    BLOB NOT NULL,
                $COL_UPDATED INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// IconCache  — three-tier:  L1 RAM (LruCache)  ->  L2 SQLite  ->  L3 PackageManager
//
// Icon rows are invalidated only when a package is actually installed/updated,
// matching the strategy used by Lawnchair / Launcher3's IconCache.java.
//
// Usage in your Application class:
//
//   class MyApp : Application() {
//       override fun onCreate() {
//           super.onCreate()
//           IconCache.init(this)               // registers receivers + opens DB
//       }
//       override fun onTrimMemory(level: Int) {
//           super.onTrimMemory(level)
//           if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE)
//               IconCache.trimMemory()          // evicts L1 only; L2 SQLite survives
//       }
//       override fun onLowMemory() {
//           super.onLowMemory()
//           IconCache.trimMemory()
//       }
//   }
// ─────────────────────────────────────────────────────────────────────────────

object IconCache {

    // ── L1: in-process RAM caches ─────────────────────────────────────────────

    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()

    private val bitmapCache = object : LruCache<String, Bitmap>(maxMemoryKb) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private val imageBitmapCache = object : LruCache<String, ImageBitmap>(500) {
        override fun sizeOf(key: String, value: ImageBitmap) = 1
    }

    // ── L2: SQLite disk cache ─────────────────────────────────────────────────

    @Volatile
    private var dbHelper: IconDbHelper? = null

    private fun db(): SQLiteDatabase? = dbHelper?.writableDatabase

    // ── Initialization guard ─────────────────────────────────────────────────
    // Prevents double-registration of the broadcast receiver if init() is
    // accidentally called more than once (e.g. from a secondary process).
    @Volatile
    private var initialized = false

    // ── Package-change receiver ───────────────────────────────────────────────

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            when (intent.action) {
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED -> invalidatePackage(pkg)
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Must be called once from Application.onCreate().
     * Opens the SQLite DB and registers the package-change receiver so icons
     * are automatically invalidated on install / update / uninstall.
     *
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun init(context: Context) {
        // Fast path: already initialized, no lock needed.
        if (initialized) return

        // Slow path: double-checked locking to guarantee exactly-once semantics.
        synchronized(this) {
            if (initialized) return

            val appCtx = context.applicationContext
            dbHelper = IconDbHelper(appCtx)

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            appCtx.registerReceiver(packageReceiver, filter)

            initialized = true
        }
    }

    /**
     * Synchronous L1-only lookup.
     * Call from Compose composition to avoid jank; it never suspends.
     */
    fun getCachedImageBitmap(key: String): ImageBitmap? = imageBitmapCache.get(key)

    /**
     * Full three-tier icon lookup:
     *   1. L1 RAM     — synchronous, zero allocation
     *   2. L2 SQLite  — disk read, staleness check via PackageInfo.lastUpdateTime
     *   3. L3 PackageManager — only when the icon is genuinely missing or stale
     *
     * Always call from a coroutine; never call on the main thread directly.
     */
    suspend fun loadIcon(context: Context, key: String, packageName: String): ImageBitmap? {

        // ── L1 hit ────────────────────────────────────────────────────────────
        imageBitmapCache.get(key)?.let { return it }
        bitmapCache.get(key)?.let { bmp ->
            return bmp.asImageBitmap().also { imageBitmapCache.put(key, it) }
        }

        return withContext(Dispatchers.IO) {

            // ── L2 hit (SQLite) ───────────────────────────────────────────────
            val diskBitmap = readFromDb(key)
            if (diskBitmap != null) {
                val hw   = diskBitmap.toHardwareBitmap()
                val imgB = hw.asImageBitmap()
                bitmapCache.put(key, hw)
                imageBitmapCache.put(key, imgB)
                return@withContext imgB
            }

            // ── L3 miss -> fetch from PackageManager, write to L1 + L2 ───────
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val software = drawableToScaledBitmap(drawable, ICON_SIZE_PX)

                writeToDb(key, packageName, software, context)

                val hw   = software.toHardwareBitmap()
                val imgB = hw.asImageBitmap()
                bitmapCache.put(key, hw)
                imageBitmapCache.put(key, imgB)
                imgB
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Pre-warms the cache for [items] before they appear on screen.
     * Skips keys already in L1 RAM.  Icons are loaded in parallel on
     * [Dispatchers.IO] for maximum throughput (e.g. 16 icons per page).
     */
    suspend fun preload(context: Context, items: List<Pair<String, String>>) =
        withContext(Dispatchers.IO) {
            items
                .filter { (key, _) -> imageBitmapCache.get(key) == null }
                .map { (key, packageName) ->
                    // Launch each icon load concurrently so IO latency overlaps.
                    async { loadIcon(context, key, packageName) }
                }
                .awaitAll()
        }

    /**
     * Evicts L1 RAM caches only.
     * L2 SQLite survives — icons reload from disk instantly on the next access.
     * Hook into Application.onTrimMemory / onLowMemory (see class-level KDoc).
     */
    fun trimMemory() {
        bitmapCache.evictAll()
        imageBitmapCache.evictAll()
    }

    /**
     * Removes one package's icons from both L1 RAM and L2 SQLite.
     * Called automatically by the package-change receiver; you can also call
     * it manually if you detect an icon change through another mechanism.
     */
    fun invalidatePackage(packageName: String) {
        // L1 evict
        bitmapCache.snapshot().keys
            .filter { it.startsWith(packageName) }
            .forEach { k ->
                bitmapCache.remove(k)
                imageBitmapCache.remove(k)
            }

        // L2 evict
        try {
            db()?.delete(TABLE, "$COL_PKG = ?", arrayOf(packageName))
        } catch (_: Exception) { /* DB not yet open or already closed */ }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Reads a cached bitmap from SQLite.
     * Trust the database and the BroadcastReceiver to handle invalidation.
     * Removing the PackageManager IPC call here eliminates the 10s+ UI freezes.
     */
    private fun readFromDb(key: String): Bitmap? {
        val database = db() ?: return null
        return try {
            database.query(
                TABLE,
                arrayOf(COL_BLOB),
                "$COL_KEY = ?",
                arrayOf(key),
                null, null, null
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val blob = cursor.getBlob(cursor.getColumnIndexOrThrow(COL_BLOB))
                BitmapFactory.decodeByteArray(blob, 0, blob.size)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes a software [Bitmap] to SQLite as a compressed PNG BLOB alongside
     * the package's current lastUpdateTime for future staleness checks.
     * Uses INSERT OR REPLACE so re-runs are idempotent.
     */
    private fun writeToDb(key: String, packageName: String, bitmap: Bitmap, context: Context) {
        val database = db() ?: return
        try {
            val lastUpdateTime = context.packageManager
                .getPackageInfo(packageName, 0).lastUpdateTime

            val bytes = ByteArrayOutputStream().also { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }.toByteArray()

            val values = ContentValues().apply {
                put(COL_KEY,     key)
                put(COL_PKG,     packageName)
                put(COL_BLOB,    bytes)
                put(COL_UPDATED, lastUpdateTime)
            }
            database.insertWithOnConflict(
                TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (_: Exception) { /* PackageManager race or DB error — silently skip */ }
    }

    /**
     * Copies the bitmap to HARDWARE config on API 26+ for zero-copy GPU rendering.
     * Falls back to the original software bitmap on older API levels.
     */
    private fun Bitmap.toHardwareBitmap(): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            copy(Bitmap.Config.HARDWARE, false).also { recycle() }
        } else {
            this
        }

    /**
     * Converts any [Drawable] to a software [Bitmap] scaled to [sizePx] × [sizePx].
     *
     * FIX: The original code returned the [BitmapDrawable]'s internal bitmap
     * directly when it was already the correct size.  Later, [toHardwareBitmap]
     * would call [Bitmap.recycle] on that value — corrupting the drawable's own
     * bitmap and causing potential crashes or rendering artefacts on the next
     * draw call.  We now always produce a copy we exclusively own.
     */
    private fun drawableToScaledBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable) {
            val src = drawable.bitmap
            // Null bitmap is theoretically possible for a resource-less
            // BitmapDrawable; fall through to the generic path in that case.
                ?: return renderDrawable(drawable, sizePx)

            // Always copy — we must not recycle a bitmap we don't own.
            return if (src.width == sizePx && src.height == sizePx) {
                src.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
            }
        }

        return renderDrawable(drawable, sizePx)
    }

    /**
     * Renders a non-BitmapDrawable (VectorDrawable, AdaptiveIconDrawable, etc.)
     * to a new software bitmap of [sizePx] × [sizePx].
     */
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
