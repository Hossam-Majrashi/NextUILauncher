package com.nextui.launcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class LauncherRepository(
    private val context: Context,
    private val appsDataSource: InstalledAppsDataSource = InstalledAppsDataSource(context)
) {
    private val _items = MutableStateFlow<List<LauncherItemEntity>>(emptyList())
    private val _folders = MutableStateFlow<List<FolderEntity>>(emptyList())
    
    private val persistenceFile: File get() = File(context.filesDir, "launcher_data_v2.json")

    suspend fun initialize() = withContext(Dispatchers.IO) {
        loadFromDisk()
    }

    fun getAll(): Flow<List<LauncherItemEntity>> = _items.asStateFlow()
    fun getFolders(): Flow<List<FolderEntity>> = _folders.asStateFlow()

    suspend fun syncInstalledApps() = withContext(Dispatchers.IO) {
        val freshApps = appsDataSource.load()
        val freshKeys = freshApps.mapTo(HashSet()) { it.componentKey }

        _items.update { existing ->
            val existingMap = existing.associateBy { it.componentKey }
            val synced = freshApps.map { fresh ->
                existingMap[fresh.componentKey]?.copy(isInstalled = true) ?: fresh
            }
            val uninstalledApps = existing
                .filter { it.componentKey !in freshKeys && it.isInstalled }
                .map { it.copy(isInstalled = false) }
            synced + uninstalledApps
        }
        saveToDisk()
    }

    suspend fun upsert(item: LauncherItemEntity) {
        _items.update { list ->
            val index = list.indexOfFirst { it.componentKey == item.componentKey }
            if (index != -1) list.toMutableList().apply { set(index, item) }
            else list + item
        }
        saveToDisk()
    }

    /**
     * Batch upsert: one state emission + one disk write for N items.
     * Used by pinned-list reordering, which previously triggered N sequential
     * saves (and N full-state rebuilds) for a single drag gesture.
     */
    suspend fun upsertAll(items: List<LauncherItemEntity>) {
        if (items.isEmpty()) return
        val byKey = items.associateBy { it.componentKey }
        _items.update { list ->
            val keys = byKey.keys
            val updated = list.map { byKey[it.componentKey] ?: it }
            val existing = list.mapTo(HashSet()) { it.componentKey }
            updated + items.filter { it.componentKey !in existing }
        }
        saveToDisk()
    }

    suspend fun delete(item: LauncherItemEntity) {
        _items.update { list -> list.filter { it.componentKey != item.componentKey } }
        saveToDisk()
    }

    // ── Folder Management ─────────────────────────────────────────────────────
    
    suspend fun createFolder(name: String) {
        _folders.update { it + FolderEntity(id = UUID.randomUUID().toString(), name = name) }
        saveToDisk()
    }

    suspend fun deleteFolder(folderId: String) {
        _folders.update { it.filter { f -> f.id != folderId } }
        _items.update { items -> items.map { if (it.folderId == folderId) it.copy(folderId = null) else it } }
        saveToDisk()
    }

    suspend fun exportJson(): String = withContext(Dispatchers.Default) {
        val root = JSONObject()
        root.put("items", JSONArray(serializeItems(_items.value)))
        root.put("folders", JSONArray(serializeFolders(_folders.value)))
        val notesStr = context.getSharedPreferences("launcher_notes", Context.MODE_PRIVATE).getString("notes_data", "[]") ?: "[]"
        root.put("notes", JSONArray(notesStr))
        root.toString()
    }

    suspend fun importJson(json: String): Result<Int> = withContext(Dispatchers.Default) {
        runCatching {
            val root = JSONObject(json)
            if (root.has("items")) {
                val restoredItems = deserializeItems(root.getJSONArray("items").toString())
                _items.update { (restoredItems + it).distinctBy { item -> item.componentKey } }
            }
            if (root.has("folders")) {
                val restoredFolders = deserializeFolders(root.getJSONArray("folders").toString())
                _folders.update { (restoredFolders + it).distinctBy { f -> f.id } }
            }
            if (root.has("notes")) {
                context.getSharedPreferences("launcher_notes", Context.MODE_PRIVATE).edit().putString("notes_data", root.getJSONArray("notes").toString()).apply()
            }
            saveToDisk()
            _items.value.size
        }
    }

    // Serializes disk writes so concurrent upserts (pin + reorder + hide in
    // quick succession) never interleave partial JSON or duplicate work.
    private val saveMutex = Mutex()

    private suspend fun saveToDisk() {
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = JSONObject()
                    root.put("items", JSONArray(serializeItems(_items.value)))
                    root.put("folders", JSONArray(serializeFolders(_folders.value)))
                    persistenceFile.writeText(root.toString())
                }
            }
        }
    }

    private suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        runCatching {
            if (!persistenceFile.exists()) {
                // Try legacy load
                val legacy = File(context.filesDir, "launcher_items.json")
                if (legacy.exists()) {
                    val items = deserializeItems(legacy.readText())
                    _items.value = items
                    saveToDisk()
                }
                return@runCatching
            }
            val root = JSONObject(persistenceFile.readText())
            _items.value = deserializeItems(root.optJSONArray("items")?.toString() ?: "[]")
            _folders.value = deserializeFolders(root.optJSONArray("folders")?.toString() ?: "[]")
        }
    }

    private fun serializeItems(items: List<LauncherItemEntity>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id); put("label", item.label); put("packageName", item.packageName)
                put("className", item.className); put("isPinned", item.isPinned)
                put("sortOrder", item.sortOrder); put("isHidden", item.isHidden)
                item.customCategory?.let { put("customCategory", it) }
                item.folderId?.let { put("folderId", it) }
            })
        }
        return arr.toString()
    }

    private fun deserializeItems(json: String): List<LauncherItemEntity> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            LauncherItemEntity(
                id = obj.getString("id"), label = obj.getString("label"),
                packageName = obj.getString("packageName"), className = obj.optString("className", ""),
                isPinned = obj.optBoolean("isPinned", false), sortOrder = obj.optInt("sortOrder", 0),
                isHidden = obj.optBoolean("isHidden", false),
                customCategory = if (obj.has("customCategory") && !obj.isNull("customCategory")) obj.getString("customCategory") else null,
                folderId = if (obj.has("folderId") && !obj.isNull("folderId")) obj.getString("folderId") else null
            )
        }
    }

    private fun serializeFolders(folders: List<FolderEntity>): String {
        val arr = JSONArray()
        folders.forEach { f ->
            arr.put(JSONObject().apply { put("id", f.id); put("name", f.name) })
        }
        return arr.toString()
    }

    private fun deserializeFolders(json: String): List<FolderEntity> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            FolderEntity(id = obj.getString("id"), name = obj.getString("name"))
        }
    }
}
