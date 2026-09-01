package com.nextui.launcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.LabelOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextui.launcher.DiagnosticsLogger
import com.nextui.launcher.data.IconCache
import com.nextui.launcher.data.LauncherItemEntity
import com.nextui.launcher.ui.components.AppCell
import com.nextui.launcher.ui.components.AppIcon
import com.nextui.launcher.ui.components.PinnedAppsList
import com.nextui.launcher.ui.components.RecycleCell
import com.nextui.launcher.ui.components.SwipeHint
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ── Constants ──────────────────────────────────────────────────────────────────
private const val DEV_NAME  = "Hossam Majrashi"
private const val DEV_EMAIL = "hossam.majrashi@gmail.com"
private const val DEV_WEB   = "https://hossam-majrashi.github.io/Works/"

// ── Root screen ───────────────────────────────────────────────────────────────
//
// Performance architecture (see OPTIMIZATION_PROMPT):
//  • ALL item menus/dialogs are hoisted here: the entire grid renders ZERO
//    DropdownMenu/AlertDialog instances. One long-press → one shared sheet.
//  • The pager never busy-waits; page-count changes apply after the current
//    scroll settles via a snapshotFlow suspension (no 16 ms polling loop).
//  • Icon prefetch is settle-aware: whenever the pager settles, ±2 adjacent
//    pages are pre-warmed into the icon cache (velocity-tolerant window).
//  • Package broadcasts are handled app-wide (LauncherApp → EventBus → VM),
//    not here — this composable has no receiver to leak.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(
    stateFlow:              StateFlow<LauncherUiState>,
    homeEvents:             SharedFlow<Unit>? = null,
    onSearchChange:         (String) -> Unit,
    onSelectCategory:       (String) -> Unit,
    onTogglePinned:         (LauncherItemEntity) -> Unit,
    onMovePinned:           (Int, Int) -> Unit,
    onSetCustomCategory:    (LauncherItemEntity, String?) -> Unit,
    onLaunchApp:            (LauncherItemEntity) -> Unit,
    onBackup:               (Uri) -> Unit,
    onRestore:              (Uri) -> Unit,
    onRemoveItem:           (LauncherItemEntity) -> Unit,
    onOpenInStore:          (LauncherItemEntity) -> Unit,
    onToggleHidden:         (LauncherItemEntity) -> Unit,
    onCreateFolder:         (String) -> Unit,
    onMoveToFolder:         (LauncherItemEntity, String?) -> Unit,
    onToggleHideAppsInFolders: (Boolean) -> Unit,
    onRestoreMessageShown:  () -> Unit
) {
    val state         by stateFlow.collectAsStateWithLifecycle()
    val context       = LocalContext.current
    val focusManager  = LocalFocusManager.current
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val coroutineScope = rememberCoroutineScope()

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onBackup) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onRestore) }

    // ── Hoisted contextual UI state (ONE menu/sheet for the whole screen) ────
    var menuItem           by remember { mutableStateOf<LauncherItemEntity?>(null) }
    var recycleMenuItem    by remember { mutableStateOf<LauncherItemEntity?>(null) }
    var categoryDialogItem by remember { mutableStateOf<LauncherItemEntity?>(null) }
    var folderPickerItem   by remember { mutableStateOf<LauncherItemEntity?>(null) }
    var diagnosticsOpen    by remember { mutableStateOf(false) }
    var hiddenAppsOpen     by remember { mutableStateOf(false) }

    val prefs     = remember { context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableIntStateOf(prefs.getInt("theme_mode", 0)) }

    val isDark = when (themeMode) {
        1    -> false
        2    -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()

    // ── Pager setup ───────────────────────────────────────────────────────────
    // Root pager has exactly 2 pages:
    //  • Page 0: Home page (Clock, Hijri date, Pinned apps, Notes)
    //  • Page 1: App Drawer (Single unified Search bar, Categories, and App grid pages)
    val rootPagerState = rememberPagerState { 2 }
    val drawerPagerState = rememberPagerState { state.drawerPageCount }

    LaunchedEffect(homeEvents) {
        homeEvents?.collect {
            focusManager.clearFocus()
            if (rootPagerState.currentPage != 0) {
                rootPagerState.scrollToPage(0)
            }
        }
    }

    // ── Settle-aware icon prefetch (around the settled drawer page) ──────────
    LaunchedEffect(drawerPagerState) {
        snapshotFlow { drawerPagerState.settledPage to state.drawerPagedApps }.collect { (settled, paged) ->
            val items = ((settled - 2)..(settled + 3))
                .filter { it in paged.indices }
                .flatMap { paged[it] }
                .map { it.componentKey to it.packageName }
            if (items.isNotEmpty()) IconCache.preload(context, items)
        }
    }

    val handleBackToHome = remember(coroutineScope, rootPagerState) {
        {
            focusManager.clearFocus()
            onSearchChange("")
            coroutineScope.launch {
                rootPagerState.scrollToPage(0)
            }
        }
    }

    BackHandler(enabled = true) {
        if (state.query.isNotBlank()) {
            // When searching: clear search and stay in app drawer
            focusManager.clearFocus()
            onSearchChange("")
        } else if (rootPagerState.currentPage != 0) {
            // When in drawer: back returns to Home
            handleBackToHome()
        }
    }

    val hiddenList = remember(state.all) {
        state.all.filter { it.isHidden && it.isInstalled }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color    = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                if (state.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                if (state.isRefreshing && !state.loading) {
                    LinearProgressIndicator(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                    )
                }

                HorizontalPager(
                    state                   = rootPagerState,
                    modifier                = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { page ->
                    if (page == 0) {
                        HomePageContent(
                            state             = state,
                            themeMode         = themeMode,
                            onThemeModeChange = { next ->
                                themeMode = next
                                prefs.edit().putInt("theme_mode", next).apply()
                            },
                            onBackup          = { backupLauncher.launch("nextui-launcher-backup.json") },
                            onRestore         = { restoreLauncher.launch(arrayOf("application/json")) },
                            onOpenHiddenApps  = { hiddenAppsOpen = true },
                            onOpenDiagnostics = { diagnosticsOpen = true },
                            onLaunchApp       = onLaunchApp,
                            onMovePinned      = onMovePinned,
                            onCreateFolder    = onCreateFolder,
                            onToggleHideAppsInFolders = onToggleHideAppsInFolders
                        )
                    } else {
                        AppDrawerContent(
                            state             = state,
                            drawerPagerState  = drawerPagerState,
                            navBarPadding     = navBarPadding,
                            onSearchChange    = onSearchChange,
                            onSelectCategory  = onSelectCategory,
                            onLaunchApp       = onLaunchApp,
                            onShowAppMenu     = { menuItem = it },
                            onShowRecycleMenu = { recycleMenuItem = it },
                            onOpenInStore     = onOpenInStore,
                            onBackPress       = {
                                if (state.query.isNotBlank()) {
                                    focusManager.clearFocus()
                                    onSearchChange("")
                                } else {
                                    handleBackToHome()
                                }
                            }
                        )
                    }
                }
            }

            // ── Hoisted app-item menu (single shared bottom sheet) ──────────
            menuItem?.let { item ->
                ItemMenuSheet(
                    item        = item,
                    onDismiss   = { menuItem = null },
                    onPin       = { onTogglePinned(item) },
                    onHide      = { onToggleHidden(item) },
                    onAppInfo   = { openAppInfo(context, item) },
                    onSetCategory = { categoryDialogItem = item },
                    onResetCategory = if (item.customCategory != null) {
                        { onSetCustomCategory(item, null) }
                    } else null,
                    onMoveToFolder = { folderPickerItem = item }
                )
            }

            // ── Hoisted recycle-bin menu ─────────────────────────────────────
            recycleMenuItem?.let { item ->
                RecycleMenuSheet(
                    item        = item,
                    onDismiss   = { recycleMenuItem = null },
                    onOpenStore = { onOpenInStore(item) },
                    onAppInfo   = { openAppInfo(context, item) },
                    onDelete    = { onRemoveItem(item) }
                )
            }

            // ── Hoisted category dialog (single shared text field) ──────────
            categoryDialogItem?.let { item ->
                var input by remember(item.componentKey) {
                    mutableStateOf(item.customCategory ?: "")
                }
                AlertDialog(
                    onDismissRequest = { categoryDialogItem = null },
                    title            = { Text("Set Category") },
                    text             = {
                        OutlinedTextField(
                            value         = input,
                            onValueChange = { input = it },
                            label         = { Text("Category name") },
                            singleLine    = true,
                            shape         = RoundedCornerShape(12.dp)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (input.trim().isNotEmpty()) onSetCustomCategory(item, input.trim())
                            categoryDialogItem = null
                        }) { Text("Set") }
                    },
                    dismissButton = {
                        TextButton(onClick = { categoryDialogItem = null }) { Text("Cancel") }
                    }
                )
            }

            // ── Hoisted folder picker ────────────────────────────────────────
            folderPickerItem?.let { item ->
                AlertDialog(
                    onDismissRequest = { folderPickerItem = null },
                    title = { Text("Move to group") },
                    text  = {
                        if (state.folders.isEmpty()) {
                            Text("No groups created yet. Create one in Settings.")
                        } else {
                            LazyColumn {
                                if (item.folderId != null) {
                                    item(key = "remove_from_group") {
                                        MenuActionRow(
                                            icon  = Icons.Rounded.FolderOff,
                                            label = "Remove from group",
                                            onClick = {
                                                onMoveToFolder(item, null)
                                                folderPickerItem = null
                                            }
                                        )
                                    }
                                }
                                items(state.folders, key = { it.id }) { folder ->
                                    MenuActionRow(
                                        icon  = Icons.Rounded.Folder,
                                        label = folder.name,
                                        onClick = {
                                            onMoveToFolder(item, folder.id)
                                            folderPickerItem = null
                                        }
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { folderPickerItem = null }) { Text("Cancel") }
                    }
                )
            }

            // ── Restore result ───────────────────────────────────────────────
            state.restoreMessage?.let { msg ->
                AlertDialog(
                    onDismissRequest = onRestoreMessageShown,
                    confirmButton    = {
                        TextButton(onClick = onRestoreMessageShown) { Text("OK") }
                    },
                    text = { Text(msg) }
                )
            }

            // ── Hidden apps dialog ───────────────────────────────────────────
            if (hiddenAppsOpen) {
                AlertDialog(
                    onDismissRequest = { hiddenAppsOpen = false },
                    confirmButton    = {
                        TextButton(onClick = { hiddenAppsOpen = false }) { Text("Close") }
                    },
                    title = { Text("Hidden Apps") },
                    text  = {
                        if (hiddenList.isEmpty()) {
                            Text("No hidden apps.")
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(items = hiddenList, key = { it.componentKey }) { item ->
                                    Row(
                                        modifier              = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment     = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment     = Alignment.CenterVertically
                                        ) {
                                            AppIcon(item = item, disabled = false)
                                            Text(item.label, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        IconButton(onClick = { onToggleHidden(item) }) {
                                            Icon(
                                                Icons.Rounded.Visibility, "Unhide",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }

            // ── Diagnostics dialog ───────────────────────────────────────────
            if (diagnosticsOpen) {
                AlertDialog(
                    onDismissRequest = { diagnosticsOpen = false },
                    confirmButton    = {
                        TextButton(onClick = { diagnosticsOpen = false }) { Text("Close") }
                    },
                    title = { Text("Diagnostics") },
                    text  = {
                        val log = DiagnosticsLogger.getLog()
                        if (log.isEmpty()) {
                            Text("No events recorded yet.")
                        } else {
                            SelectionContainer {
                                Text(text = log, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                )
            }
        }
    }
}

// ── Shared menu sheets ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemMenuSheet(
    item: LauncherItemEntity,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onAppInfo: () -> Unit,
    onSetCategory: () -> Unit,
    onResetCategory: (() -> Unit)?,
    onMoveToFolder: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        MenuSheetHeader(item.label)
        MenuActionRow(Icons.Rounded.FolderZip, "Move to group") { onDismiss(); onMoveToFolder() }
        MenuActionRow(
            Icons.Rounded.PushPin,
            if (item.isPinned) "Unpin from home" else "Pin to home"
        ) { onDismiss(); onPin() }
        MenuActionRow(Icons.Rounded.VisibilityOff, "Hide app") { onDismiss(); onHide() }
        MenuActionRow(Icons.Rounded.Info, "App info") { onDismiss(); onAppInfo() }
        MenuActionRow(Icons.AutoMirrored.Rounded.Label, "Set category") { onDismiss(); onSetCategory() }
        if (onResetCategory != null) {
            MenuActionRow(Icons.AutoMirrored.Rounded.LabelOff, "Reset category") { onDismiss(); onResetCategory() }
        }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecycleMenuSheet(
    item: LauncherItemEntity,
    onDismiss: () -> Unit,
    onOpenStore: () -> Unit,
    onAppInfo: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        MenuSheetHeader(item.label)
        MenuActionRow(Icons.Rounded.ShoppingCart, "Open in Play Store") { onDismiss(); onOpenStore() }
        MenuActionRow(Icons.Rounded.Info, "App info") { onDismiss(); onAppInfo() }
        HorizontalDivider()
        MenuActionRow(
            icon = Icons.Rounded.DeleteForever,
            label = "Delete from Recycle Bin",
            tint = MaterialTheme.colorScheme.error
        ) { onDismiss(); onDelete() }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

@Composable
private fun MenuSheetHeader(title: String) {
    Text(
        title,
        style      = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines   = 1,
        modifier   = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

/** Single reusable action row for sheets & pickers. */
@Composable
private fun MenuActionRow(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

// ── Home page ─────────────────────────────────────────────────────────────────
@Composable
private fun HomePageContent(
    state:             LauncherUiState,
    themeMode:         Int,
    onThemeModeChange: (Int) -> Unit,
    onBackup:          () -> Unit,
    onRestore:         () -> Unit,
    onOpenHiddenApps:  () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onLaunchApp:       (LauncherItemEntity) -> Unit,
    onMovePinned:      (Int, Int) -> Unit,
    onCreateFolder:    (String) -> Unit,
    onToggleHideAppsInFolders: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var settingsMenuExpanded  by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showAdvancedSettings  by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.Top
        ) {
            HeaderSection(Modifier.weight(1f))

            Box {
                IconButton(onClick = { settingsMenuExpanded = true }) {
                    Icon(
                        Icons.Rounded.Settings, "Settings",
                        modifier = Modifier.size(28.dp),
                        tint     = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(
                    expanded         = settingsMenuExpanded,
                    onDismissRequest = { settingsMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text        = { Text("Create Group") },
                        leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                        onClick     = { settingsMenuExpanded = false; showCreateFolderDialog = true }
                    )
                    DropdownMenuItem(
                        text        = { Text("Backup data") },
                        leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) },
                        onClick     = { settingsMenuExpanded = false; onBackup() }
                    )
                    DropdownMenuItem(
                        text        = { Text("Restore data") },
                        leadingIcon = { Icon(Icons.Rounded.RestorePage, null) },
                        onClick     = { settingsMenuExpanded = false; onRestore() }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (themeMode) {
                                    1    -> "Theme: Light"
                                    2    -> "Theme: Dark"
                                    else -> "Theme: Auto"
                                }
                            )
                        },
                        leadingIcon = {
                            Icon(
                                when (themeMode) {
                                    1    -> Icons.Rounded.LightMode
                                    2    -> Icons.Rounded.DarkMode
                                    else -> Icons.Rounded.BrightnessAuto
                                }, null
                            )
                        },
                        onClick = { onThemeModeChange((themeMode + 1) % 3) }
                    )
                    DropdownMenuItem(
                        text        = { Text("Hidden apps") },
                        leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                        onClick     = { settingsMenuExpanded = false; onOpenHiddenApps() }
                    )
                    DropdownMenuItem(
                        text        = { Text("Diagnostics log") },
                        leadingIcon = { Icon(Icons.Rounded.BugReport, null) },
                        onClick     = { settingsMenuExpanded = false; onOpenDiagnostics() }
                    )
                    DropdownMenuItem(
                        text        = { Text("Advanced Settings") },
                        leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                        onClick     = { settingsMenuExpanded = false; showAdvancedSettings = true }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(DEV_NAME, fontWeight = FontWeight.Bold)
                                Text(
                                    DEV_EMAIL,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = { Icon(Icons.Rounded.Email, null) },
                        onClick     = { settingsMenuExpanded = false; openEmail(context, DEV_EMAIL) }
                    )
                    DropdownMenuItem(
                        text        = { Text("Visit Website") },
                        leadingIcon = { Icon(Icons.Rounded.Public, null) },
                        onClick     = { settingsMenuExpanded = false; openUrl(context, DEV_WEB) }
                    )
                }
            }
        }

        if (state.pinned.isNotEmpty()) {
            PinnedAppsList(
                items    = state.pinned,
                onLaunch = onLaunchApp,
                onMove   = onMovePinned,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            )
        } else if (!state.loading) {
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No pinned apps", style = MaterialTheme.typography.bodyLarge)
                    Text("Swipe right to see all apps", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        NotesSection(restoreKey = state.isRefreshing)
        Spacer(Modifier.weight(1f))
    }

    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title            = { Text("Create Group") },
            text             = {
                OutlinedTextField(
                    value         = folderName,
                    onValueChange = { folderName = it },
                    label         = { Text("Group Name") },
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (folderName.isNotBlank()) onCreateFolder(folderName)
                    showCreateFolderDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAdvancedSettings) {
        AlertDialog(
            onDismissRequest = { showAdvancedSettings = false },
            title = { Text("Advanced Settings") },
            text  = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hide apps in groups from 'All'", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "When enabled, apps that belong to a group will not be shown in the 'All' category.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.hideAppsInFolders,
                        onCheckedChange = { onToggleHideAppsInFolders(it) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAdvancedSettings = false }) { Text("Close") }
            }
        )
    }
}

// ── Header ────────────────────────────────────────────────────────────────────
@Composable
private fun HeaderSection(modifier: Modifier = Modifier) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            val now     = System.currentTimeMillis()
            val nextDay = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                add(java.util.Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            kotlinx.coroutines.delay(nextDay - now)
            currentTime = System.currentTimeMillis()
        }
    }

    val gregorianFmt = remember {
        java.text.SimpleDateFormat("EEEE, d MMMM (M) yyyy", java.util.Locale.getDefault())
    }
    val arabicFmt = remember {
        java.text.SimpleDateFormat("EEEE", java.util.Locale.forLanguageTag("ar"))
    }

    val dateStr   = remember(currentTime) { gregorianFmt.format(java.util.Date(currentTime)) }
    val arabicDay = remember(currentTime) { arabicFmt.format(java.util.Date(currentTime)) }

    val hijriStr = remember(currentTime) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val islamicCal = android.icu.util.IslamicCalendar().apply { timeInMillis = currentTime }
            val day        = islamicCal.get(android.icu.util.IslamicCalendar.DAY_OF_MONTH)
            val month      = islamicCal.get(android.icu.util.IslamicCalendar.MONTH)
            val year       = islamicCal.get(android.icu.util.IslamicCalendar.YEAR)
            val monthNames = arrayOf(
                "Muharram", "Safar", "Rabi' I", "Rabi' II",
                "Jumada I", "Jumada II", "Rajab", "Sha'ban",
                "Ramadan", "Shawwal", "Dhu al-Qi'dah", "Dhu al-Hijjah"
            )
            val monthName = monthNames.getOrNull(month) ?: (month + 1).toString()
            "$day $monthName (${month + 1}) $year AH"
        } else ""
    }

    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.Top
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "NextUI",
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.primary
            )
            Text(dateStr, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (hijriStr.isNotEmpty()) {
                Text(hijriStr, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            arabicDay,
            style      = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.primary
        )
    }
}

// ── App Drawer content (Single unified Search bar, Categories, and App grid pages) ────
@Composable
private fun AppDrawerContent(
    state:             LauncherUiState,
    drawerPagerState:  androidx.compose.foundation.pager.PagerState,
    navBarPadding:    Dp,
    onSearchChange:    (String) -> Unit,
    onSelectCategory: (String) -> Unit,
    onLaunchApp:      (LauncherItemEntity) -> Unit,
    onShowAppMenu:    (LauncherItemEntity) -> Unit,
    onShowRecycleMenu: (LauncherItemEntity) -> Unit,
    onOpenInStore:    (LauncherItemEntity) -> Unit,
    onBackPress:       () -> Unit
) {
    val gridState      = rememberLazyGridState()
    var recycleBinOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Single unified search bar (created once, blazing fast, zero duplicate overhead)
        GlobalSearchBar(
            query             = state.query,
            recycleCount      = state.recycleApps.size,
            showScrollToBin   = state.recycleApps.isNotEmpty(),
            onSearchChange    = onSearchChange,
            onRecycleBinClick = { recycleBinOpen = true },
            onBackToHome      = onBackPress
        )

        // Single unified category bar
        if (state.query.isBlank() && state.categories.size > 1) {
            LazyRow(
                modifier              = Modifier.fillMaxWidth(),
                contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.categories) { category ->
                    FilterChip(
                        selected = category == state.selectedCategory,
                        onClick  = { onSelectCategory(category) },
                        label    = { Text(category) }
                    )
                }
            }
        }

        if (recycleBinOpen) {
            RecycleBinSheet(
                recycleApps = state.recycleApps,
                onDismiss   = { recycleBinOpen = false },
                onOpenStore = onOpenInStore,
                onShowMenu  = onShowRecycleMenu
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val isSearching = state.query.isNotBlank()

            // Pager is ALWAYS composed with STABLE data — never torn down,
            // never changes during search, so clearing search is instant.
            HorizontalPager(
                state                   = drawerPagerState,
                modifier                = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2,
                userScrollEnabled       = !isSearching
            ) { pageIndex ->
                val pageApps = state.drawerPagedApps.getOrNull(pageIndex)
                if (pageApps != null) {
                    LazyVerticalGrid(
                        columns           = GridCells.Fixed(4),
                        modifier          = Modifier.fillMaxSize(),
                        contentPadding    = PaddingValues(
                            start  = 8.dp,
                            end    = 8.dp,
                            top    = 4.dp,
                            bottom = navBarPadding + 36.dp
                        ),
                        verticalArrangement   = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items       = pageApps,
                            key         = { it.componentKey },
                            contentType = { "app_cell" }
                        ) { item ->
                            AppCell(
                                item       = item,
                                onLaunch   = onLaunchApp,
                                onShowMenu = onShowAppMenu
                            )
                        }

                        val rem = pageApps.size % 4
                        if (rem != 0) {
                            items(count = 4 - rem, contentType = { "app_filler" }) {
                                Box(Modifier.aspectRatio(0.75f))
                            }
                        }
                    }
                }
            }

            if (!isSearching && state.drawerPageCount > 1) {
                SwipeHint(
                    pagerState = drawerPagerState,
                    modifier   = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navBarPadding + 4.dp)
                )
            }

            // Search results overlay — covers the pager visually while active.
            if (isSearching) {
                val searchApps = state.pagedApps.flatten()
                if (searchApps.isEmpty()) {
                    Box(
                        modifier         = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No results for '${state.query}'",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns           = GridCells.Fixed(4),
                        state             = gridState,
                        modifier          = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentPadding    = PaddingValues(
                            start  = 8.dp,
                            end    = 8.dp,
                            top    = 4.dp,
                            bottom = navBarPadding + 16.dp
                        ),
                        verticalArrangement   = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items       = searchApps,
                            key         = { it.componentKey },
                            contentType = { "app_cell" }
                        ) { item ->
                            AppCell(
                                item       = item,
                                onLaunch   = onLaunchApp,
                                onShowMenu = onShowAppMenu
                            )
                        }

                        val rem = searchApps.size % 4
                        if (rem != 0) {
                            items(count = 4 - rem, contentType = { "app_filler" }) {
                                Box(Modifier.aspectRatio(0.75f))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Global search bar ─────────────────────────────────────────────────────────
//
// Uses TextFieldValue to manage exact selection and BiDi text composition,
// preventing Arabic cursor jumps or character overlapping during recomposition.
@Composable
private fun GlobalSearchBar(
    query:             String,
    recycleCount:      Int,
    showScrollToBin:   Boolean,
    onSearchChange:    (String) -> Unit,
    onRecycleBinClick: () -> Unit,
    onBackToHome:      () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current

    // TextFieldValue preserves cursor position & BiDi composition across recompositions.
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = query, selection = TextRange(query.length)))
    }

    // Sync external changes (Home press, back button clear, etc.).
    LaunchedEffect(query) {
        if (query != textFieldValue.text) {
            textFieldValue = TextFieldValue(
                text = query,
                selection = TextRange(query.length)
            )
        }
    }

    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value         = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                onSearchChange(newValue.text)
            },
            modifier      = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Back && event.type == KeyEventType.KeyUp) {
                        onBackToHome()
                        true
                    } else false
                },
            singleLine    = true,
            textStyle     = LocalTextStyle.current.copy(
                textDirection = TextDirection.Content
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { focusManager.clearFocus() }
            ),
            leadingIcon   = { Icon(Icons.Rounded.Search, null) },
            trailingIcon  = {
                if (textFieldValue.text.isNotEmpty()) {
                    IconButton(onClick = {
                        textFieldValue = TextFieldValue("", TextRange.Zero)
                        onSearchChange("")
                    }) {
                        Icon(Icons.Rounded.Clear, "Clear")
                    }
                }
            },
            placeholder = { Text("Search apps…") },
            shape       = RoundedCornerShape(20.dp)
        )

        if (showScrollToBin) {
            BadgedBox(badge = {
                if (recycleCount > 0) Badge { Text("$recycleCount") }
            }) {
                IconButton(onClick = onRecycleBinClick) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Recycle Bin",
                        tint = if (recycleCount > 0)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Notes section ─────────────────────────────────────────────────────────────
@Composable
private fun NotesSection(restoreKey: Boolean = false) {
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences("launcher_notes", Context.MODE_PRIVATE) }

    fun loadNotes(): List<String> {
        val str = prefs.getString("notes_data", "") ?: ""
        return try {
            if (str.startsWith("[")) {
                val arr = org.json.JSONArray(str)
                (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
            } else {
                str.split("|~|").filter { it.isNotBlank() }
            }
        } catch (_: Exception) { emptyList() }
    }

    var notes by remember(restoreKey) { mutableStateOf(loadNotes()) }

    fun saveNotes(newNotes: List<String>) {
        notes = newNotes
        val arr = org.json.JSONArray()
        newNotes.forEach { arr.put(it) }
        prefs.edit().putString("notes_data", arr.toString()).apply()
    }

    var isAdding     by remember { mutableStateOf(false) }
    var inputText    by remember { mutableStateOf("") }
    var noteToDelete by remember { mutableStateOf<String?>(null) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var editingText  by remember { mutableStateOf("") }

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 12.dp)
    ) {
        notes.forEachIndexed { index, note ->
            if (editingIndex == index) {
                OutlinedTextField(
                    value         = editingText,
                    onValueChange = { editingText = it },
                    modifier      = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    placeholder   = { Text("Edit note...") },
                    trailingIcon  = {
                        IconButton(onClick = {
                            if (editingText.isNotBlank()) {
                                val newList = notes.toMutableList()
                                newList[index] = editingText.trim()
                                saveNotes(newList)
                            }
                            editingIndex = -1
                        }) { Icon(Icons.Rounded.Check, "Save") }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (editingText.isNotBlank()) {
                            val newList = notes.toMutableList()
                            newList[index] = editingText.trim()
                            saveNotes(newList)
                        }
                        editingIndex = -1
                    }),
                    singleLine = true,
                    shape      = RoundedCornerShape(12.dp)
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier          = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                ) {
                    Icon(
                        Icons.Rounded.RadioButtonUnchecked, "Delete",
                        tint     = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { noteToDelete = note }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        note,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { editingIndex = index; editingText = note }
                    )
                }
            }
        }

        if (isAdding) {
            OutlinedTextField(
                value         = inputText,
                onValueChange = { inputText = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                placeholder   = { Text("Add note...") },
                trailingIcon  = {
                    IconButton(onClick = {
                        if (inputText.isNotBlank()) saveNotes(notes + inputText.trim())
                        inputText = ""
                        isAdding  = false
                    }) { Icon(Icons.Rounded.Check, "Save") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (inputText.isNotBlank()) saveNotes(notes + inputText.trim())
                    inputText = ""
                    isAdding  = false
                }),
                singleLine = true,
                shape      = RoundedCornerShape(12.dp)
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { isAdding = true }
                    .padding(vertical = 8.dp, horizontal = 8.dp)
            ) {
                Icon(
                    Icons.Rounded.Add, "Add Note",
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Add note...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    noteToDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title            = { Text("Delete note?") },
            text             = { Text(note) },
            confirmButton    = {
                TextButton(onClick = {
                    saveNotes(notes.filter { it != note })
                    noteToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Recycle bin bottom sheet ───────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecycleBinSheet(
    recycleApps: ImmutableList<LauncherItemEntity>,
    onDismiss:   () -> Unit,
    onOpenStore: (LauncherItemEntity) -> Unit,
    onShowMenu:  (LauncherItemEntity) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Delete, null,
                modifier = Modifier.size(20.dp),
                tint     = MaterialTheme.colorScheme.error
            )
            Text(
                "Recycle Bin (${recycleApps.size})",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.error
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        if (recycleApps.isEmpty()) {
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Recycle bin is empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns           = GridCells.Fixed(4),
                modifier          = Modifier.fillMaxWidth(),
                contentPadding    = PaddingValues(
                    start  = 8.dp,
                    end    = 8.dp,
                    top    = 4.dp,
                    bottom = 24.dp
                ),
                verticalArrangement   = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items       = recycleApps,
                    key         = { "sheet_${it.componentKey}" },
                    contentType = { "recycle_cell" }
                ) { item ->
                    RecycleCell(
                        item        = item,
                        onOpenStore = onOpenStore,
                        onShowMenu  = onShowMenu
                    )
                }

                val rem = recycleApps.size % 4
                if (rem != 0) {
                    items(count = 4 - rem) { Box(Modifier.aspectRatio(0.75f)) }
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────
private fun openAppInfo(context: Context, item: LauncherItemEntity) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${item.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}

private fun openEmail(context: Context, email: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}
