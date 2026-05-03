package com.nextui.launcher.data

data class LauncherItemEntity(
    val id: String,
    val label: String,
    val packageName: String,
    val className: String = "",
    val category: String = "General",
    val isInstalled: Boolean = true,
    val isPinned: Boolean = false,
    val sortOrder: Int = 0,
    val customCategory: String? = null,
    val isHidden: Boolean = false,
    val folderId: String? = null
) {
    val componentKey: String
        get() = "$packageName/$className"
}

data class FolderEntity(
    val id: String,
    val name: String,
    val items: List<LauncherItemEntity> = emptyList()
)
