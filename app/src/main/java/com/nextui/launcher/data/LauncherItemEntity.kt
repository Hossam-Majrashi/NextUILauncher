package com.nextui.launcher.data

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Immutable model for a single launcher item (app shortcut).
 *
 * Annotated [@Immutable] so the Compose compiler treats every instance as
 * stable: composables that take this type become *skippable*, and unchanged
 * cells are never recomposed during scrolls or state updates.
 *
 * All mutations are performed via `copy(...)`; identity-based change detection
 * (===) is therefore sufficient and allocation-cheap.
 */
@Immutable
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
    /**
     * Stable cache/identity key: "package/class".
     * Used as the LazyGrid item key, the icon-cache key, and drag-and-drop id.
     */
    val componentKey: String
        get() = "$packageName/$className"
}

/**
 * Immutable folder (a.k.a. "group") model.
 *
 * [items] is an [ImmutableList] so the Compose compiler can smart-skip any
 * composable that renders folder content when the underlying list instance
 * has not changed.
 */
@Immutable
data class FolderEntity(
    val id: String,
    val name: String,
    val items: ImmutableList<LauncherItemEntity> = persistentListOf()
)
