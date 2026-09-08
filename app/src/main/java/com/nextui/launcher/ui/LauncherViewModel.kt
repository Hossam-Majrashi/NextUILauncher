package com.nextui.launcher.ui

import java.text.Collator

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nextui.launcher.DiagnosticsLogger
import com.nextui.launcher.LagPhase
import com.nextui.launcher.LauncherEventBus
import com.nextui.launcher.data.FolderEntity
import com.nextui.launcher.data.IconCache
import com.nextui.launcher.data.LauncherItemEntity
import com.nextui.launcher.data.LauncherRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Granular UI state.
 *
 * Every list is an [ImmutableList] and the class is [@Immutable], so the
 * Compose compiler smart-skips any composable whose inputs are unchanged
 * instances — a pin toggle or search keystroke no longer recomposes the
 * grid, the home page, or the pager.
 */
@Immutable
data class LauncherUiState(
    val loading: Boolean = true,
    val isRefreshing: Boolean = false,
    val showOnboarding: Boolean = false,
    val restoreMessage: String? = null,
    val all: ImmutableList<LauncherItemEntity> = persistentListOf(),
    val categories: ImmutableList<String> = persistentListOf(),
    val selectedCategory: String = "All",
    val query: String = "",
    val pinned: ImmutableList<LauncherItemEntity> = persistentListOf(),
    val folders: ImmutableList<FolderEntity> = persistentListOf(),
    /** Search results when searching; same as [drawerPagedApps] otherwise. */
    val pagedApps: ImmutableList<ImmutableList<LauncherItemEntity>> = persistentListOf(),
    /** Drawer pages — stable, NEVER changes during search. Used by the pager. */
    val drawerPagedApps: ImmutableList<ImmutableList<LauncherItemEntity>> = persistentListOf(),
    val recycleApps: ImmutableList<LauncherItemEntity> = persistentListOf(),
    val pageCount: Int = 1,
    /** Drawer page count — stable, NEVER changes during search. Used by pager state. */
    val drawerPageCount: Int = 1,
    val hideAppsInFolders: Boolean = false
)

/** Secondary flags, combined as one flow so [combine] stays within arity. */
private data class UiFlags(
    val selectedCategory: String,
    val hideAppsInFolders: Boolean,
    val isRefreshing: Boolean,
    val restoreMessage: String?,
    val showOnboarding: Boolean
)

/** Raw data inputs shared by every derived list. */
private data class CoreInputs(
    val apps: List<LauncherItemEntity>,
    val folders: List<FolderEntity>,
    val initialSyncDone: Boolean,
    val flags: UiFlags
)

/**
 * LauncherViewModel — reactive, background-first state pipeline.
 *
 * ── Architecture ────────────────────────────────────────────────────────────
 * Two-tier derivation keeps search instant while heavy work is cached:
 *
 *   repository flows ─┐
 *   flags             ─┴─► combine ─► computeBaseState() ─► baseState (cached)
 *                                                              │
 *   _query (instant)  ──────────────────────────► combine ─► applyQuery() ─► uiState
 *
 * • Tier 1 (baseState): categories, folders, pinned, sorted default list — runs
 *   ONLY when apps/folders/flags change, never on keystrokes.
 * • Tier 2 (uiState): combines the cached base with the instant query. The
 *   search filter uses a pre-built lowercase index — zero allocations, instant.
 * • Mutations ONLY write to the repository; the UI reacts through flows.
 * • Package broadcasts arrive via [LauncherEventBus], are debounced (batch
 *   updates fire dozens of events), and trigger a conflated [refresh].
 * • After first data, pinned apps + the first 3 drawer pages are pre-warmed
 *   into the icon cache ahead of time (AOT).
 */
@OptIn(FlowPreview::class)
class LauncherViewModel(
    application: Application,
    private val repository: LauncherRepository
) : AndroidViewModel(application) {

    private val appContext get() = getApplication<Application>()
    private val prefs = application.getSharedPreferences("launcher_prefs", android.content.Context.MODE_PRIVATE)

    // ── Input flows (single sources of truth) ────────────────────────────────

    private val _query             = MutableStateFlow("")
    private val _selectedCategory  = MutableStateFlow("All")
    private val _hideAppsInFolders = MutableStateFlow(prefs.getBoolean("hide_apps_in_folders", false))
    private val _isRefreshing      = MutableStateFlow(false)
    private val _restoreMessage    = MutableStateFlow<String?>(null)
    private val _showOnboarding    = MutableStateFlow(!prefs.getBoolean("onboarding_complete", false))

    /** Flips true after the first PackageManager sync — keeps the splash /
     *  loading state alive until real data exists (cold-start gate). */
    private val _initialSyncDone   = MutableStateFlow(false)

    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    // Pre-lowercased search index: avoids allocating lowercase copies on every
    // keystroke. Rebuilt only when the app list changes (install/uninstall).
    @Volatile
    private var searchIndex = emptyMap<String, Pair<String, String>>()

    // ── Outputs ──────────────────────────────────────────────────────────────

    private val flags = combine(
        _selectedCategory, _hideAppsInFolders, _isRefreshing, _restoreMessage, _showOnboarding
    ) { category, hide, refreshing, restoreMsg, onboarding ->
        UiFlags(category, hide, refreshing, restoreMsg, onboarding)
    }

    private val coreInputs = combine(
        repository.getAll(), repository.getFolders(), _initialSyncDone, flags
    ) { apps, folders, syncDone, f -> CoreInputs(apps, folders, syncDone, f) }

    /** Tier 1: heavy computation cached — only reruns on app/folder/flag changes. */
    private val baseState: StateFlow<BaseState> =
        coreInputs
            .map { computeBaseState(it) }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = BaseState()
            )

    /** Tier 2: instant query overlay on the cached base — no debounce. */
    val uiState: StateFlow<LauncherUiState> =
        combine(baseState, _query) { base, query ->
            applyQuery(base, query)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = LauncherUiState()
            )

    private val _homeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val homeEvents: SharedFlow<Unit> = _homeEvents.asSharedFlow()

    private var refreshJob: Job? = null

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        // Cold-start pipeline: load persisted state → sync with PackageManager.
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching {
                repository.initialize()
                repository.syncInstalledApps()
            }.onFailure { e ->
                DiagnosticsLogger.recordError("INIT", e.message ?: "error", e)
            }
            _initialSyncDone.value = true
            _isRefreshing.value = false
        }

        // Rebuild the search index whenever the underlying app list changes.
        viewModelScope.launch {
            repository.getAll().collect { apps ->
                searchIndex = apps.associate { app ->
                    app.componentKey to Pair(
                        app.label.lowercase(),
                        app.packageName.lowercase()
                    )
                }
            }
        }

        // Package broadcast pipeline: debounce bursts (batch Play-Store
        // updates) into a single conflated refresh.
        viewModelScope.launch {
            LauncherEventBus.events
                .filterIsInstance<LauncherEventBus.Event.PackagesChanged>()
                .debounce(400L)
                .collect { refresh() }
        }

        // AOT icon pre-warm: as soon as the first real state exists, warm the
        // pinned apps and the first three drawer pages into the icon cache.
        viewModelScope.launch {
            val first = uiState.first { !it.loading }
            DiagnosticsLogger.recordPhase(
                LagPhase.DATA_LOADED,
                "${first.all.size} items / ${first.pageCount} pages"
            )
            val targets = (first.pinned + first.pagedApps.take(3).flatten())
                .map { it.componentKey to it.packageName }
            IconCache.preload(appContext, targets)
        }
    }

    // ── Mutations (repository-only; UI reacts via flows) ─────────────────────

    fun createFolder(name: String) {
        viewModelScope.launch { repository.createFolder(name) }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch { repository.deleteFolder(folderId) }
    }

    fun moveToFolder(item: LauncherItemEntity, folderId: String?) {
        viewModelScope.launch { repository.upsert(item.copy(folderId = folderId)) }
    }

    fun togglePinned(item: LauncherItemEntity) {
        viewModelScope.launch { repository.upsert(item.copy(isPinned = !item.isPinned)) }
    }

    fun movePinned(from: Int, to: Int) {
        val pinned = uiState.value.pinned
        if (from !in pinned.indices || to !in pinned.indices) return
        val reordered = pinned.toMutableList()
            .apply { add(to, removeAt(from)) }
            .mapIndexed { i, a -> a.copy(sortOrder = i) }
        viewModelScope.launch { repository.upsertAll(reordered) }
    }

    fun setCustomCategory(item: LauncherItemEntity, category: String?) {
        viewModelScope.launch { repository.upsert(item.copy(customCategory = category)) }
    }

    fun removeItem(item: LauncherItemEntity) {
        viewModelScope.launch { repository.delete(item) }
    }

    fun toggleHidden(item: LauncherItemEntity) {
        viewModelScope.launch {
            // Hiding also unpins; unhiding restores visibility only.
            repository.upsert(
                item.copy(
                    isHidden = !item.isHidden,
                    isPinned = if (!item.isHidden) false else item.isPinned
                )
            )
        }
    }

    fun setHideAppsInFolders(hide: Boolean) {
        prefs.edit().putBoolean("hide_apps_in_folders", hide).apply()
        _hideAppsInFolders.value = hide
    }

    // ── Search & category selection ──────────────────────────────────────────

    fun onSearchChange(query: String) {
        // Instant — directly drives the lightweight Tier 2 combine.
        // No debounce: applyQuery() is fast enough (pre-indexed search).
        _query.value = query
    }

    fun onSelectCategory(cat: String) {
        _selectedCategory.value = cat
    }

    // ── Refresh pipeline ─────────────────────────────────────────────────────

    fun refresh() {
        // Conflate: a new refresh supersedes an in-flight one.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            DiagnosticsLogger.increment(DiagnosticsLogger.Metrics.REFRESH_COUNT)
            val t0 = System.nanoTime()
            _isRefreshing.value = true
            runCatching { repository.syncInstalledApps() }
                .onFailure { e ->
                    DiagnosticsLogger.recordError("REFRESH", e.message ?: "error", e)
                }
            _isRefreshing.value = false
            DiagnosticsLogger.recordPhase(
                LagPhase.REFRESH, "sync", "${(System.nanoTime() - t0) / 1_000_000}ms"
            )
        }
    }

    // ── Misc UI events ───────────────────────────────────────────────────────

    fun onRestoreMessageShown() {
        _restoreMessage.value = null
    }

    fun completeOnboarding() {
        prefs.edit().putBoolean("onboarding_complete", true).apply()
        _showOnboarding.value = false
    }

    fun onHomePressed() {
        if (_query.value.isNotEmpty()) _query.value = ""
        if (_selectedCategory.value != "All") _selectedCategory.value = "All"
        _homeEvents.tryEmit(Unit)
    }

    // ── External actions ─────────────────────────────────────────────────────

    fun launchApp(item: LauncherItemEntity) {
        if (!item.isInstalled) {
            openUrl("https://play.google.com/store/apps/details?id=${item.packageName}")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.nanoTime()
            runCatching {
                val intent = if (item.className.isNotBlank()) {
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName(item.packageName, item.className)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }
                } else {
                    val pm = appContext.packageManager
                    (pm.getLaunchIntentForPackage(item.packageName) ?: run {
                        val fallback = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            setPackage(item.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (pm.resolveActivity(fallback, 0) != null) fallback else null
                    })?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }
                }

                if (intent != null) {
                    withContext(Dispatchers.Main) {
                        appContext.startActivity(intent)
                        DiagnosticsLogger.recordPhase(
                            LagPhase.LAUNCH,
                            "${(System.nanoTime() - t0) / 1_000_000}ms",
                            item.label
                        )
                    }
                }
            }
        }
    }

    fun openInStore(item: LauncherItemEntity) {
        openUrl("https://play.google.com/store/apps/details?id=${item.packageName}")
    }

    fun backup(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = repository.exportJson()
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }
            }
        }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                }
                if (json.isEmpty()) return@runCatching
                repository.importJson(json).onSuccess { count ->
                    _restoreMessage.value = "Restored $count apps"
                    refresh()
                }.onFailure { e ->
                    _restoreMessage.value = "Restore failed: ${e.message}"
                }
            }
        }
    }

    // ── Tier 1: heavy base-state derivation (runs on Dispatchers.Default) ─────
    //
    // Everything that does NOT depend on the search query: category extraction,
    // folder enrichment, default sorted list, pinned list, recycle list.
    // This is cached and only recomputed when apps/folders/flags change.

    /** Pre-computed state independent of the search query. */
    private data class BaseState(
        val loading: Boolean = true,
        val isRefreshing: Boolean = false,
        val showOnboarding: Boolean = false,
        val restoreMessage: String? = null,
        val all: ImmutableList<LauncherItemEntity> = persistentListOf(),
        val installedApps: List<LauncherItemEntity> = emptyList(),
        val recycleApps: ImmutableList<LauncherItemEntity> = persistentListOf(),
        val enrichedFolders: ImmutableList<FolderEntity> = persistentListOf(),
        val categories: ImmutableList<String> = persistentListOf(),
        val selectedCategory: String = "All",
        val pinned: ImmutableList<LauncherItemEntity> = persistentListOf(),
        val defaultPagedApps: ImmutableList<ImmutableList<LauncherItemEntity>> = persistentListOf(),
        val defaultPageCount: Int = 1,
        val hideAppsInFolders: Boolean = false
    )

    private fun computeBaseState(core: CoreInputs): BaseState {
        val apps  = core.apps
        val flags = core.flags

        val installedApps = apps.filter { it.isInstalled && !it.isHidden }
        val recycleApps   = apps.filter { !it.isInstalled }

        val enrichedFolders = core.folders.map { folder ->
            folder.copy(
                items = apps.filter { it.folderId == folder.id && it.isInstalled }
                    .toImmutableList()
            )
        }

        val folderNames         = enrichedFolders.map { it.name }
        val extractedCategories = installedApps
            .map { it.customCategory ?: it.category }
            .filter { it != "General" }
            .distinct()
            .sorted()
        val finalCategories = (listOf("All") + extractedCategories + folderNames)
            .distinct()
            .toImmutableList()
        val selectedCat = if (finalCategories.contains(flags.selectedCategory)) {
            flags.selectedCategory
        } else {
            "All"
        }

        // Pre-compute the default (no-query) sorted list.
        val byCategory = when {
            selectedCat == "All" -> {
                if (flags.hideAppsInFolders) installedApps.filter { it.folderId == null }
                else installedApps
            }
            enrichedFolders.any { it.name == selectedCat } ->
                enrichedFolders.first { it.name == selectedCat }.items
            else -> installedApps.filter { (it.customCategory ?: it.category) == selectedCat }
        }
        val pinned   = byCategory.filter { it.isPinned }.sortedBy { it.sortOrder }
        val unpinned = byCategory.filter { !it.isPinned }.sortedWith(compareBy(collator) { it.label })
        val defaultSorted = pinned + unpinned

        val appsPerPage = 16
        val defaultPaged = defaultSorted.chunked(appsPerPage)
            .map { it.toImmutableList() }
            .toImmutableList()

        return BaseState(
            loading            = !core.initialSyncDone,
            isRefreshing       = flags.isRefreshing,
            showOnboarding     = flags.showOnboarding,
            restoreMessage     = flags.restoreMessage,
            all                = apps.toImmutableList(),
            installedApps      = installedApps,
            recycleApps        = recycleApps.toImmutableList(),
            enrichedFolders    = enrichedFolders.toImmutableList(),
            categories         = finalCategories,
            selectedCategory   = selectedCat,
            pinned             = apps
                .filter { it.isPinned && it.isInstalled && !it.isHidden }
                .sortedBy { it.sortOrder }
                .toImmutableList(),
            defaultPagedApps   = defaultPaged,
            defaultPageCount   = maxOf(1, defaultPaged.size),
            hideAppsInFolders  = flags.hideAppsInFolders
        )
    }

    // ── Tier 2: instant query overlay (lightweight, no debounce) ─────────────
    //
    // Only runs the search filter + chunk when query is non-empty.
    // When query is empty, returns the pre-computed base state directly.

    private fun applyQuery(base: BaseState, query: String): LauncherUiState {
        val queryTrimmed = query.trim()

        val searchPagedApps: ImmutableList<ImmutableList<LauncherItemEntity>>
        val searchPageCount: Int

        if (queryTrimmed.isEmpty()) {
            // Fast path: use pre-computed paged apps from Tier 1.
            searchPagedApps = base.defaultPagedApps
            searchPageCount = base.defaultPageCount
        } else {
            DiagnosticsLogger.increment(DiagnosticsLogger.Metrics.SEARCH_RUN_COUNT)
            // Lightweight filter using pre-built lowercase index.
            val queryLower = queryTrimmed.lowercase()
            val idx = searchIndex
            val filtered = base.installedApps.filter { app ->
                val cached = idx[app.componentKey]
                if (cached != null) {
                    cached.first.contains(queryLower) || cached.second.contains(queryLower)
                } else {
                    app.label.contains(queryTrimmed, ignoreCase = true) ||
                        app.packageName.contains(queryTrimmed, ignoreCase = true)
                }
            }.sortedWith(compareBy(collator) { it.label })

            val appsPerPage = 16
            searchPagedApps = filtered.chunked(appsPerPage)
                .map { it.toImmutableList() }
                .toImmutableList()
            searchPageCount = maxOf(1, searchPagedApps.size)
        }

        return LauncherUiState(
            loading            = base.loading,
            isRefreshing       = base.isRefreshing,
            showOnboarding     = base.showOnboarding,
            restoreMessage     = base.restoreMessage,
            all                = base.all,
            categories         = base.categories,
            selectedCategory   = base.selectedCategory,
            query              = query,
            pinned             = base.pinned,
            folders            = base.enrichedFolders,
            pagedApps          = searchPagedApps,
            drawerPagedApps    = base.defaultPagedApps,
            recycleApps        = base.recycleApps,
            pageCount          = searchPageCount,
            drawerPageCount    = base.defaultPageCount,
            hideAppsInFolders  = base.hideAppsInFolders
        )
    }

    private fun openUrl(url: String) {
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
