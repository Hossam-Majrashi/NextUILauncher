package com.nextui.launcher.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nextui.launcher.DiagnosticsLogger
import com.nextui.launcher.LagPhase
import com.nextui.launcher.data.FolderEntity
import com.nextui.launcher.data.LauncherItemEntity
import com.nextui.launcher.data.LauncherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LauncherUiState(
    val loading: Boolean = true,
    val isRefreshing: Boolean = false,
    val showOnboarding: Boolean = false,
    val restoreMessage: String? = null,
    val all: List<LauncherItemEntity> = emptyList(),
    val categories: List<String> = emptyList(),
    val selectedCategory: String = "All",
    val query: String = "",
    val pinned: List<LauncherItemEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val pagedApps: List<List<LauncherItemEntity>> = emptyList(),
    val recycleApps: List<LauncherItemEntity> = emptyList(),
    val pageCount: Int = 1,
    val hideAppsInFolders: Boolean = false
)

class LauncherViewModel(
    application: Application,
    private val repository: LauncherRepository
) : AndroidViewModel(application) {

    private val appContext get() = getApplication<Application>()

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val _homeEvents = MutableSharedFlow<Unit>()
    val homeEvents: SharedFlow<Unit> = _homeEvents.asSharedFlow()

    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private val prefs = application.getSharedPreferences("launcher_prefs", android.content.Context.MODE_PRIVATE)

    init {
        val onboardingDone = prefs.getBoolean("onboarding_complete", false)
        val hideAppsInFolders = prefs.getBoolean("hide_apps_in_folders", false)
        _uiState.update { it.copy(showOnboarding = !onboardingDone, hideAppsInFolders = hideAppsInFolders) }

        viewModelScope.launch {
            repository.initialize()
            _uiState.update { it.copy(loading = true) }
            
            launch {
                repository.getFolders().collect { folders ->
                    val apps = _uiState.value.all
                    val newState = rebuildStateSync(_uiState.value, apps, _uiState.value.query, folders)
                    _uiState.value = newState
                }
            }

            runCatching {
                repository.syncInstalledApps()
                val apps = repository.getAll().first()
                val folders = repository.getFolders().first()
                val newState = rebuildStateSync(_uiState.value, apps, _uiState.value.query, folders)
                _uiState.value = newState
            }.onFailure { e ->
                DiagnosticsLogger.recordError("INIT", e.message ?: "error")
                _uiState.update { it.copy(loading = false) }
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch { repository.createFolder(name) }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch { repository.deleteFolder(folderId) }
    }

    fun moveToFolder(item: LauncherItemEntity, folderId: String?) {
        viewModelScope.launch {
            val updated = item.copy(folderId = folderId)
            repository.upsert(updated)
            val newAll = _uiState.value.all.map {
                if (it.componentKey == item.componentKey) updated else it
            }
            val folders = repository.getFolders().first()
            val newState = rebuildStateSync(_uiState.value, newAll, _uiState.value.query, folders)
            _uiState.value = newState
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val isFirstLoad = _uiState.value.all.isEmpty()
            _uiState.update {
                if (isFirstLoad) it.copy(loading = true)
                else it.copy(isRefreshing = true)
            }
            runCatching {
                repository.syncInstalledApps()
                val apps = repository.getAll().first()
                val folders = repository.getFolders().first()
                val newState = rebuildStateSync(_uiState.value, apps, _uiState.value.query, folders)
                _uiState.value = newState
            }.onFailure { e ->
                DiagnosticsLogger.recordError("REFRESH", e.message ?: "error")
                _uiState.update { it.copy(loading = false, isRefreshing = false) }
            }
        }
    }

    fun onSearchChange(query: String) {
        // 1. تحديث نص البحث فوراً في الواجهة لضمان ظهور الأحرف أثناء الكتابة
        _uiState.update { it.copy(query = query) }

        // 2. تأخير عملية الفلترة الثقيلة (Debounce) لضمان عدم تعليق الجهاز
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(150L) 
            val apps = _uiState.value.all
            val folders = repository.getFolders().first()
            val newState = withContext(Dispatchers.Default) {
                rebuildStateSync(_uiState.value, apps, query, folders)
            }
            _uiState.value = newState
        }
    }

    fun onSelectCategory(cat: String) {
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.value = rebuildStateSync(
                current.copy(selectedCategory = cat),
                current.all,
                current.query,
                current.folders
            )
        }
    }

    fun setHideAppsInFolders(hide: Boolean) {
        prefs.edit().putBoolean("hide_apps_in_folders", hide).apply()
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.value = rebuildStateSync(
                current.copy(hideAppsInFolders = hide),
                current.all,
                current.query,
                current.folders
            )
        }
    }

    fun onRestoreMessageShown() {
        _uiState.update { it.copy(restoreMessage = null) }
    }

    fun togglePinned(item: LauncherItemEntity) {
        viewModelScope.launch {
            val updated = item.copy(isPinned = !item.isPinned)
            repository.upsert(updated)
            val newAll = _uiState.value.all.map {
                if (it.componentKey == item.componentKey) updated else it
            }
            val folders = repository.getFolders().first()
            val newState = rebuildStateSync(_uiState.value, newAll, _uiState.value.query, folders)
            _uiState.value = newState
        }
    }

    fun movePinned(from: Int, to: Int) {
        val pinned = _uiState.value.pinned.toMutableList()
        if (from !in pinned.indices || to !in pinned.indices) return

        val moved = pinned.removeAt(from)
        pinned.add(to, moved)
        val reordered = pinned.mapIndexed { i, a -> a.copy(sortOrder = i) }

        viewModelScope.launch {
            reordered.forEach { repository.upsert(it) }
            val newAll = _uiState.value.all.map { a ->
                reordered.find { it.componentKey == a.componentKey } ?: a
            }
            val folders = repository.getFolders().first()
            val newState = rebuildStateSync(_uiState.value, newAll, _uiState.value.query, folders)
            _uiState.value = newState
        }
    }

    fun setCustomCategory(item: LauncherItemEntity, category: String?) {
        viewModelScope.launch {
            val updated = item.copy(customCategory = category)
            repository.upsert(updated)
            val newAll = _uiState.value.all.map {
                if (it.componentKey == item.componentKey) updated else it
            }
            val folders = repository.getFolders().first()
            val newState = rebuildStateSync(_uiState.value, newAll, _uiState.value.query, folders)
            _uiState.value = newState
        }
    }

    fun removeItem(item: LauncherItemEntity) {
        viewModelScope.launch {
            repository.delete(item)
            val newAll = _uiState.value.all.filter { it.componentKey != item.componentKey }
            val folders = repository.getFolders().first()
            val newState = rebuildStateSync(_uiState.value, newAll, _uiState.value.query, folders)
            _uiState.value = newState
        }
    }

    fun toggleHidden(item: LauncherItemEntity) {
        viewModelScope.launch {
            // Unpin if hiding
            val updated = item.copy(isHidden = !item.isHidden, isPinned = if (!item.isHidden) false else item.isPinned)
            repository.upsert(updated)
            val newAll = _uiState.value.all.map {
                if (it.componentKey == item.componentKey) updated else it
            }
            val folders = repository.getFolders().first()
            val newState = rebuildStateSync(_uiState.value, newAll, _uiState.value.query, folders)
            _uiState.value = newState
        }
    }

    fun launchApp(item: LauncherItemEntity) {
        DiagnosticsLogger.recordError("LAUNCH_A", "'${item.label}' installed=${item.isInstalled}")
        if (!item.isInstalled) {
            openUrl("https://play.google.com/store/apps/details?id=${item.packageName}")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            runCatching {
                val pm = appContext.packageManager
                val intent = pm.getLaunchIntentForPackage(item.packageName)
                val resolved = intent ?: run {
                    val fallback = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage(item.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val activity = pm.resolveActivity(fallback, 0)
                    if (activity != null) fallback else null
                }
                withContext(Dispatchers.Main) {
                    resolved?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (resolved != null) {
                        appContext.startActivity(resolved)
                        DiagnosticsLogger.recordPhase(LagPhase.LAUNCH, (System.currentTimeMillis() - t0).toString(), item.label)
                    }
                }
            }
        }
    }

    fun openInStore(item: LauncherItemEntity) {
        openUrl("https://play.google.com/store/apps/details?id=${item.packageName}")
    }

    fun completeOnboarding() {
        prefs.edit().putBoolean("onboarding_complete", true).apply()
        _uiState.update { it.copy(showOnboarding = false) }
    }

    fun onHomePressed() {
        searchJob?.cancel()
        viewModelScope.launch {
            val apps = _uiState.value.all
            val folders = repository.getFolders().first()
            val newState = rebuildStateSync(
                _uiState.value.copy(query = "", selectedCategory = "All"),
                apps,
                "",
                folders
            )
            _uiState.value = newState
            _homeEvents.emit(Unit)
        }
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
                    _uiState.update { it.copy(restoreMessage = "Restored $count apps") }
                    refresh()
                }.onFailure { e ->
                    _uiState.update { it.copy(restoreMessage = "Restore failed: ${e.message}") }
                }
            }
        }
    }

    private suspend fun rebuildStateSync(
        current: LauncherUiState,
        apps: List<LauncherItemEntity>,
        query: String,
        folders: List<FolderEntity>
    ): LauncherUiState = withContext(Dispatchers.Default) {
        val installedApps = apps.filter { it.isInstalled && !it.isHidden }
        val recycleApps   = apps.filter { !it.isInstalled }

        val enrichedFolders = folders.map { folder ->
            folder.copy(items = apps.filter { it.folderId == folder.id && it.isInstalled })
        }

        val folderNames         = enrichedFolders.map { it.name }
        val extractedCategories = installedApps.map { it.customCategory ?: it.category }
            .filter { it != "General" }
            .distinct()
            .sorted()
        val finalCategories     = (listOf("All") + extractedCategories + folderNames).distinct()
        val selectedCat         = if (finalCategories.contains(current.selectedCategory)) current.selectedCategory else "All"

        val filteredApps = if (query.isBlank()) {
            val byCategory = when {
                selectedCat == "All" -> {
                    if (current.hideAppsInFolders) {
                        installedApps.filter { it.folderId == null }
                    } else {
                        installedApps
                    }
                }
                enrichedFolders.any { it.name == selectedCat } -> {
                    enrichedFolders.first { it.name == selectedCat }.items
                }
                else -> installedApps.filter { (it.customCategory ?: it.category) == selectedCat }
            }
            val pinned   = byCategory.filter { it.isPinned }.sortedBy { it.sortOrder }
            val unpinned = byCategory.filter { !it.isPinned }.sortedBy { it.label }
            pinned + unpinned
        } else {
            installedApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }.sortedBy { it.label }
        }

        val appsPerPage = 16
        val pagedApps = if (query.isNotBlank()) {
            listOf(filteredApps)
        } else {
            filteredApps.chunked(appsPerPage)
        }

        val pageCount = maxOf(1, pagedApps.size)
        
        current.copy(
            loading = false,
            isRefreshing = false,
            all = apps,
            categories = finalCategories,
            selectedCategory = selectedCat,
            query = query,
            pinned = apps
                .filter { it.isPinned && it.isInstalled && !it.isHidden }
                .sortedBy { it.sortOrder },
            folders = enrichedFolders,
            pagedApps = pagedApps,
            recycleApps = recycleApps,
            pageCount = pageCount
        )
    }

    private fun openUrl(url: String) {
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }
}
