package com.nextui.launcher.ui

import java.text.Collator

import android.app.Application
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
    val pagedApps: ImmutableList<ImmutableList<LauncherItemEntity>> = persistentListOf(),
    val recycleApps: ImmutableList<LauncherItemEntity> = persistentListOf(),
    val pageCount: Int = 1,
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
 * There is exactly ONE derivation path:
 *
 *   repository flows ─┐
 *   query (echo)      ─┼─► combine ─► computeState() ─► uiState
 *   query (debounced) ─┤        (Dispatchers.Default)
 *   flags             ─┘
 *
 * • Mutations (pin, hide, move, …) ONLY write to the repository; the UI
 *   reacts through the flows. No manual state rebuilding, ever — which means
 *   no full-screen recomposition storms and no stale copies.
 * • Search keystrokes update [_query] instantly (text-field echo), while the
 *   expensive filtering path consumes a 100 ms-debounced twin of the flow.
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

    // ── Outputs ──────────────────────────────────────────────────────────────

    private val flags = combine(
        _selectedCategory, _hideAppsInFolders, _isRefreshing, _restoreMessage, _showOnboarding
    ) { category, hide, refreshing, restoreMsg, onboarding ->
        UiFlags(category, hide, refreshing, restoreMsg, onboarding)
    }

    private val coreInputs = combine(
        repository.getAll(), repository.getFolders(), _initialSyncDone, flags
    ) { apps, folders, syncDone, f -> CoreInputs(apps, folders, syncDone, f) }

    /** Echo query: immediate, drives the text field. */
    private val echoedQuery = _query

    /** Effective query: debounced, drives filtering. Blank clears instantly. */
    private val effectiveQuery = _query.debounce { if (it.isBlank()) 0L else 100L }

    val uiState: StateFlow<LauncherUiState> =
        combine(coreInputs, echoedQuery, effectiveQuery) { core, echo, effective ->
            computeState(core, echo, effective)
        }
            .flowOn(Dispatchers.Default)   // all filtering/sorting off the main thread
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
        // Instant echo — the StateFlow conflates rapid keystrokes for free.
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
        _query.value = ""
        _selectedCategory.value = "All"
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
                val pm = appContext.packageManager
                val intent = pm.getLaunchIntentForPackage(item.packageName)
                val resolved = intent ?: run {
                    val fallback = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage(item.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (pm.resolveActivity(fallback, 0) != null) fallback else null
                }
                withContext(Dispatchers.Main) {
                    resolved?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (resolved != null) {
                        appContext.startActivity(resolved)
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

    // ── Pure state derivation (runs on Dispatchers.Default) ──────────────────

    private fun computeState(
        core: CoreInputs,
        echoedQuery: String,
        effectiveQuery: String
    ): LauncherUiState {
        val apps   = core.apps
        val flags  = core.flags

        if (effectiveQuery.isNotBlank()) {
            DiagnosticsLogger.increment(DiagnosticsLogger.Metrics.SEARCH_RUN_COUNT)
        }

        // Locale-aware collator for proper Arabic+English alphabetical sorting.
        // PRIMARY strength ignores diacritics (tashkeel) for cleaner ordering.
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

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

        val filteredApps = if (effectiveQuery.isBlank()) {
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
            pinned + unpinned
        } else {
            installedApps.filter {
                it.label.contains(effectiveQuery, ignoreCase = true) ||
                    it.packageName.contains(effectiveQuery, ignoreCase = true)
            }.sortedWith(compareBy(collator) { it.label })
        }

        val appsPerPage = 16
        val pagedApps = (
            if (effectiveQuery.isNotBlank()) listOf(filteredApps)
            else filteredApps.chunked(appsPerPage)
        ).map { it.toImmutableList() }.toImmutableList()

        return LauncherUiState(
            loading            = !core.initialSyncDone,
            isRefreshing       = flags.isRefreshing,
            showOnboarding     = flags.showOnboarding,
            restoreMessage     = flags.restoreMessage,
            all                = apps.toImmutableList(),
            categories         = finalCategories,
            selectedCategory   = selectedCat,
            query              = echoedQuery,
            pinned             = apps
                .filter { it.isPinned && it.isInstalled && !it.isHidden }
                .sortedBy { it.sortOrder }
                .toImmutableList(),
            folders            = enrichedFolders.toImmutableList(),
            pagedApps          = pagedApps,
            recycleApps        = recycleApps.toImmutableList(),
            pageCount          = maxOf(1, pagedApps.size),
            hideAppsInFolders  = flags.hideAppsInFolders
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
