package com.nextui.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextui.launcher.DiagnosticsLogger
import com.nextui.launcher.data.FolderEntity
import com.nextui.launcher.data.IconCache
import com.nextui.launcher.data.LauncherItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ── Constants ──────────────────────────────────────────────────────────────────
private const val DEV_NAME  = "Hossam Majrashi"
private const val DEV_EMAIL = "hossam.majrashi@gmail.com"
private const val DEV_WEB   = "https://hossam-majrashi.github.io/Works/"

// ── Root screen ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(
    stateFlow:           StateFlow<LauncherUiState>,
    homeEvents:          kotlinx.coroutines.flow.SharedFlow<Unit>? = null,
    onSearchChange:      (String) -> Unit,
    onSelectCategory:    (String) -> Unit,
    onRefresh:           () -> Unit,
    onTogglePinned:      (LauncherItemEntity) -> Unit,
    onMovePinned:        (Int, Int) -> Unit,
    onSetCustomCategory: (LauncherItemEntity, String?) -> Unit,
    onLaunchApp:         (LauncherItemEntity) -> Unit,
    onBackup:            (Uri) -> Unit,
    onRestore:           (Uri) -> Unit,
    onRemoveItem:        (LauncherItemEntity) -> Unit,
    onOpenInStore:       (LauncherItemEntity) -> Unit,
    onToggleHidden:      (LauncherItemEntity) -> Unit,
    onCreateFolder:      (String) -> Unit,
    onDeleteFolder:      (String) -> Unit,
    onMoveToFolder:      (LauncherItemEntity, String?) -> Unit,
    onToggleHideAppsInFolders: (Boolean) -> Unit
) {
    val state        by stateFlow.collectAsStateWithLifecycle()
    val context       = LocalContext.current
    val focusManager  = LocalFocusManager.current
    val scope         = rememberCoroutineScope()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val backupLauncher  = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onBackup) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onRestore) }

    var diagnosticsOpen by remember { mutableStateOf(false) }
    var hiddenAppsOpen  by remember { mutableStateOf(false) }
    var indicatorWidth  by remember { mutableIntStateOf(0) }
    var isScrubbing     by remember { mutableStateOf(false) }

    val prefs     = remember { context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableIntStateOf(prefs.getInt("theme_mode", 0)) }

    val isDark = when (themeMode) {
        1    -> false
        2    -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()

    // ── Pager setup ───────────────────────────────────────────────────────────
    val livePageCount   = 1 + state.pageCount
    var stablePageCount by remember { mutableIntStateOf(livePageCount) }
    val pagerState      = rememberPagerState { stablePageCount }

    LaunchedEffect(homeEvents) {
        homeEvents?.collect {
            focusManager.clearFocus()
            pagerState.animateScrollToPage(0)
        }
    }

    LaunchedEffect(livePageCount) {
        while (pagerState.isScrollInProgress) { delay(16L) }
        stablePageCount = livePageCount
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 0 && state.query.isNotBlank()) {
            onSearchChange("")
        }
    }

    // ── Icon preloading ───────────────────────────────────────────────────────
    LaunchedEffect(pagerState.currentPage, state.pagedApps) {
        val currentPage  = pagerState.currentPage
        val appPageIndex = if (currentPage == 0) 0 else currentPage - 1

        val pagesToPreload = listOf(appPageIndex, appPageIndex + 1)
            .filter { it in 0 until state.pagedApps.size }

        val itemsToPreload = pagesToPreload.flatMap { index ->
            state.pagedApps[index].map { it.componentKey to it.packageName }
        }

        if (itemsToPreload.isNotEmpty()) {
            IconCache.preload(context, itemsToPreload)
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
            DisposableEffect(Unit) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (i.action == Intent.ACTION_PACKAGE_REMOVED &&
                            i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                        onRefresh()
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
                context.registerReceiver(receiver, filter)
                onDispose { context.unregisterReceiver(receiver) }
            }

            Box(modifier = Modifier.fillMaxSize()) {

                if (state.loading)
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (state.isRefreshing && !state.loading)
                    LinearProgressIndicator(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                    )

                HorizontalPager(
                    state                   = pagerState,
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
                            onDeleteFolder    = onDeleteFolder,
                            onMoveToFolder    = onMoveToFolder,
                            onToggleHideAppsInFolders = onToggleHideAppsInFolders
                        )
                    } else {
                        val pageIndex = page - 1
                        val pageApps  = state.pagedApps.getOrNull(pageIndex) ?: emptyList()

                        AppsGridPage(
                            apps                = pageApps,
                            recycleApps         = state.recycleApps,
                            query               = state.query,
                            categories          = state.categories,
                            selectedCategory    = state.selectedCategory,
                            folders             = state.folders,
                            onSearchChange      = onSearchChange,
                            onSelectCategory    = onSelectCategory,
                            navBarPadding       = navBarPadding,
                            onLaunchApp         = onLaunchApp,
                            onTogglePinned      = onTogglePinned,
                            onSetCustomCategory = onSetCustomCategory,
                            onToggleHidden      = onToggleHidden,
                            onOpenInStore       = onOpenInStore,
                            onRemoveItem        = onRemoveItem,
                            onMoveToFolder      = onMoveToFolder
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                        .graphicsLayer {
                            val s = if (isScrubbing) 1.1f else 1f
                            scaleX = s; scaleY = s
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer.copy(
                                alpha = if (isScrubbing) 0.8f else 0f
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .onSizeChanged { indicatorWidth = it.width }
                        .pointerInput(indicatorWidth, pagerState.pageCount) {
                            var lastTarget = -1
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    isScrubbing = true
                                    lastTarget  = -1
                                    if (indicatorWidth > 0) {
                                        val fraction   = (offset.x / indicatorWidth).coerceIn(0f, 1f)
                                        val targetPage = (fraction * (pagerState.pageCount - 1)).roundToInt()
                                        lastTarget = targetPage
                                        scope.launch { pagerState.scrollToPage(targetPage) }
                                    }
                                },
                                onDrag = { change, _ ->
                                    if (indicatorWidth > 0) {
                                        val fraction   = (change.position.x / indicatorWidth).coerceIn(0f, 1f)
                                        val targetPage = (fraction * (pagerState.pageCount - 1)).roundToInt()
                                        if (targetPage != lastTarget) {
                                            lastTarget = targetPage
                                            scope.launch { pagerState.scrollToPage(targetPage) }
                                        }
                                    }
                                },
                                onDragEnd    = { isScrubbing = false; lastTarget = -1 },
                                onDragCancel = { isScrubbing = false; lastTarget = -1 }
                            )
                        },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    repeat(pagerState.pageCount) { index ->
                        val isSelected = pagerState.currentPage == index

                        val dotSize by animateDpAsState(
                            targetValue   = if (isSelected) 8.dp else 5.dp,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label         = "dot_size_$index"
                        )
                        val dotAlpha by animateFloatAsState(
                            targetValue   = if (isSelected) 0.65f else 0.25f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label         = "dot_alpha_$index"
                        )

                        Box(
                            Modifier
                                .size(dotSize)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.onBackground.copy(alpha = dotAlpha)
                                )
                        )
                    }
                }
            }

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
                                            Text(
                                                item.label,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
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
                                Text(
                                    text  = log,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                )
            }
        }
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
    onDeleteFolder:    (String) -> Unit,
    onMoveToFolder:    (LauncherItemEntity, String?) -> Unit,
    onToggleHideAppsInFolders: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var settingsMenuExpanded by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showAdvancedSettings by remember { mutableStateOf(false) }

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
                    expanded        = settingsMenuExpanded,
                    onDismissRequest = { settingsMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text         = { Text("Create Group") },
                        leadingIcon  = { Icon(Icons.Rounded.CreateNewFolder, null) },
                        onClick      = { settingsMenuExpanded = false; showCreateFolderDialog = true }
                    )
                    DropdownMenuItem(
                        text         = { Text("Backup data") },
                        leadingIcon  = { Icon(Icons.Rounded.SaveAlt, null) },
                        onClick      = { settingsMenuExpanded = false; onBackup() }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                onLaunch = onLaunchApp,
                onMove   = onMovePinned
            )
        } else if (!state.loading && state.folders.isEmpty()) {
            Box(
                modifier        = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No pinned apps",      style = MaterialTheme.typography.bodyLarge)
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
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                }
            },
            confirmButton = {
                TextButton(onClick = { showAdvancedSettings = false }) { Text("Close") }
            }
        )
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCell(
    folder: FolderEntity,
    onLaunch: (LauncherItemEntity) -> Unit,
    onDelete: () -> Unit,
    onMoveOutOfFolder: (LauncherItemEntity, String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var contextMenuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { expanded = true },
                onLongClick = { contextMenuOpen = true }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Rounded.Folder, null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    folder.name,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "${folder.items.size} apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos, null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        
        DropdownMenu(expanded = contextMenuOpen, onDismissRequest = { contextMenuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Delete Group", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { contextMenuOpen = false; onDelete() }
            )
        }
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            confirmButton    = { TextButton({ expanded = false }) { Text("Close") } },
            title = { Text(folder.name) },
            text = {
                if (folder.items.isEmpty()) {
                    Text("No apps in this group.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(items = folder.items, key = { it.componentKey }) { item ->
                            var itemMenuOpen by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onLaunch(item); expanded = false },
                                        onLongClick = { itemMenuOpen = true }
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(item = item, disabled = false)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                
                                DropdownMenu(expanded = itemMenuOpen, onDismissRequest = { itemMenuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Move out of group") },
                                        leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) },
                                        onClick = { itemMenuOpen = false; onMoveOutOfFolder(item, null) }
                                    )
                                }
                            }
                        }
                    }
                }
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
            delay(nextDay - now)
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
            Text(
                dateStr,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (hijriStr.isNotEmpty()) {
                Text(
                    hijriStr,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
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

// ── Apps grid page ────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsGridPage(
    apps:                List<LauncherItemEntity>,
    recycleApps:         List<LauncherItemEntity>,
    query:               String,
    categories:          List<String>,
    selectedCategory:    String,
    folders:             List<FolderEntity>,
    onSearchChange:      (String) -> Unit,
    onSelectCategory:    (String) -> Unit,
    navBarPadding:       Dp,
    onLaunchApp:         (LauncherItemEntity) -> Unit,
    onTogglePinned:      (LauncherItemEntity) -> Unit,
    onSetCustomCategory: (LauncherItemEntity, String?) -> Unit,
    onToggleHidden:      (LauncherItemEntity) -> Unit,
    onOpenInStore:       (LauncherItemEntity) -> Unit,
    onRemoveItem:        (LauncherItemEntity) -> Unit,
    onMoveToFolder:      (LauncherItemEntity, String?) -> Unit
) {
    val context        = LocalContext.current
    val gridState      = rememberLazyGridState()
    var recycleBinOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        GlobalSearchBar(
            query             = query,
            recycleCount      = recycleApps.size,
            showScrollToBin   = recycleApps.isNotEmpty(),
            onSearchChange    = onSearchChange,
            onRecycleBinClick = { recycleBinOpen = true }
        )

        if (query.isBlank() && categories.size > 1) {
            LazyRow(
                modifier              = Modifier.fillMaxWidth(),
                contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { category ->
                    FilterChip(
                        selected = category == selectedCategory,
                        onClick  = { onSelectCategory(category) },
                        label    = { Text(category) }
                    )
                }
            }
        }

        if (recycleBinOpen) {
            RecycleBinSheet(
                recycleApps = recycleApps,
                onDismiss   = { recycleBinOpen = false },
                onOpenStore = onOpenInStore,
                onDelete    = onRemoveItem,
                onAppInfo   = { openAppInfo(context, it) }
            )
        }

        if (apps.isEmpty() && query.isNotBlank()) {
            Box(
                modifier        = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No results for '$query'",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns               = GridCells.Fixed(4),
                state                 = gridState,
                modifier              = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding        = PaddingValues(
                    start  = 8.dp,
                    end    = 8.dp,
                    top    = 4.dp,
                    bottom = navBarPadding + 16.dp
                ),
                verticalArrangement   = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items       = apps,
                    key         = { it.componentKey },
                    contentType = { "app_cell" }
                ) { item ->
                    AppCell(
                        item                = item,
                        folders             = folders,
                        onLaunch            = onLaunchApp,
                        onTogglePinned      = onTogglePinned,
                        onSetCustomCategory = onSetCustomCategory,
                        onAppInfo           = { openAppInfo(context, it) },
                        onToggleHidden      = onToggleHidden,
                        onMoveToFolder      = onMoveToFolder
                    )
                }

                val rem = apps.size % 4
                if (rem != 0) {
                    items(count = 4 - rem, contentType = { "app_filler" }) {
                        Box(Modifier.aspectRatio(0.75f))
                    }
                }
            }
        }
    }
}

// ── Global search bar ─────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalSearchBar(
    query:             String,
    recycleCount:      Int,
    showScrollToBin:   Boolean,
    onSearchChange:    (String) -> Unit,
    onRecycleBinClick: () -> Unit
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value         = query,
            onValueChange = onSearchChange,
            modifier      = Modifier.weight(1f),
            singleLine    = true,
            leadingIcon   = { Icon(Icons.Rounded.Search, null) },
            trailingIcon  = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
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
    val prefs = remember { context.getSharedPreferences("launcher_notes", Context.MODE_PRIVATE) }

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

    var isAdding      by remember { mutableStateOf(false) }
    var inputText     by remember { mutableStateOf("") }
    var noteToDelete  by remember { mutableStateOf<String?>(null) }
    var editingIndex  by remember { mutableIntStateOf(-1) }
    var editingText   by remember { mutableStateOf("") }

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
    recycleApps: List<LauncherItemEntity>,
    onDismiss:   () -> Unit,
    onOpenStore: (LauncherItemEntity) -> Unit,
    onDelete:    (LauncherItemEntity) -> Unit,
    onAppInfo:   (LauncherItemEntity) -> Unit
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
                modifier        = Modifier
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
                columns               = GridCells.Fixed(4),
                modifier              = Modifier.fillMaxWidth(),
                contentPadding        = PaddingValues(
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
                        onDelete    = onDelete,
                        onAppInfo   = onAppInfo
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

// ── Recycle cell ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecycleCell(
    item:        LauncherItemEntity,
    onOpenStore: (LauncherItemEntity) -> Unit,
    onDelete:    (LauncherItemEntity) -> Unit,
    onAppInfo:   (LauncherItemEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick     = { onOpenStore(item) },
                    onLongClick = { expanded = true }
                )
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(item = item, disabled = true)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                        .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f), CircleShape)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.label,
                maxLines  = 2,
                overflow  = TextOverflow.Ellipsis,
                style     = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier  = Modifier.fillMaxWidth()
            )
        }

        Box(Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text        = { Text("Open in Play Store") },
                    leadingIcon = { Icon(Icons.Rounded.ShoppingCart, null) },
                    onClick     = { expanded = false; onOpenStore(item) }
                )
                DropdownMenuItem(
                    text        = { Text("App info") },
                    leadingIcon = { Icon(Icons.Rounded.Info, null) },
                    onClick     = { expanded = false; onAppInfo(item) }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text        = {
                        Text(
                            "Delete from Recycle Bin",
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.DeleteForever, null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = { expanded = false; onDelete(item) }
                )
            }
        }
    }
}

// ── App icon ──────────────────────────────────────────────────────────────────
@Composable
private fun AppIcon(item: LauncherItemEntity, disabled: Boolean) {
    val context = LocalContext.current
    val key     = item.componentKey

    val imageBitmap by produceState<ImageBitmap?>(
        initialValue = IconCache.getCachedImageBitmap(key),
        key1         = key
    ) {
        if (value == null && item.isInstalled) {
            value = withContext(Dispatchers.IO) {
                IconCache.loadIcon(context, key, item.packageName)
            }
        }
    }

    val alpha by animateFloatAsState(
        targetValue   = if (imageBitmap != null) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label         = "icon_fade"
    )

    Box(
        modifier        = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .alpha(if (disabled) 0.5f else 1f),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier        = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                item.label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (imageBitmap != null) {
            Image(
                bitmap             = imageBitmap!!,
                contentDescription = item.label,
                modifier           = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .alpha(alpha),
                contentScale       = ContentScale.Fit
            )
        }
    }
}

// ── App cell ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(
    item:                LauncherItemEntity,
    folders:             List<FolderEntity>,
    onLaunch:            (LauncherItemEntity) -> Unit,
    onTogglePinned:      (LauncherItemEntity) -> Unit,
    onSetCustomCategory: (LauncherItemEntity, String?) -> Unit,
    onAppInfo:           (LauncherItemEntity) -> Unit,
    onToggleHidden:      (LauncherItemEntity) -> Unit,
    onMoveToFolder:      (LauncherItemEntity, String?) -> Unit
) {
    var expanded           by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var categoryInput      by remember { mutableStateOf("") }
    var showFolderSelector by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick     = { onLaunch(item) },
                    onLongClick = { expanded = true }
                )
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                AppIcon(item = item, disabled = false)
                if (item.isPinned) {
                    Icon(
                        Icons.Rounded.PushPin, null,
                        modifier = Modifier
                            .size(12.dp)
                            .offset(x = 2.dp, y = (-2).dp),
                        tint     = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.label,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
                style      = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth()
            )
        }

        Box(Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Move to group") },
                    leadingIcon = { Icon(Icons.Rounded.FolderZip, null) },
                    onClick = { expanded = false; showFolderSelector = true }
                )
                DropdownMenuItem(
                    text        = { Text(if (item.isPinned) "Unpin from home" else "Pin to home") },
                    leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                    onClick     = { expanded = false; onTogglePinned(item) }
                )
                DropdownMenuItem(
                    text        = { Text("Hide app") },
                    leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                    onClick     = { expanded = false; onToggleHidden(item) }
                )
                DropdownMenuItem(
                    text        = { Text("App info") },
                    leadingIcon = { Icon(Icons.Rounded.Info, null) },
                    onClick     = { expanded = false; onAppInfo(item) }
                )
                DropdownMenuItem(
                    text        = { Text("Set category") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Label, null) },
                    onClick     = {
                        expanded           = false
                        categoryInput      = item.customCategory ?: ""
                        showCategoryDialog = true
                    }
                )
                if (item.customCategory != null) {
                    DropdownMenuItem(
                        text        = { Text("Reset category") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.LabelOff, null) },
                        onClick     = { expanded = false; onSetCustomCategory(item, null) }
                    )
                }
            }
        }
    }

    if (showCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title            = { Text("Set Category") },
            text             = {
                OutlinedTextField(
                    value         = categoryInput,
                    onValueChange = { categoryInput = it },
                    label         = { Text("Category name") },
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (categoryInput.trim().isNotEmpty()) {
                        onSetCustomCategory(item, categoryInput.trim())
                    }
                    showCategoryDialog = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showCategoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showFolderSelector) {
        AlertDialog(
            onDismissRequest = { showFolderSelector = false },
            title = { Text("Move to group") },
            text = {
                if (folders.isEmpty()) {
                    Text("No groups created yet. Create one in Settings.")
                } else {
                    LazyColumn {
                        if (item.folderId != null) {
                            item {
                                DropdownMenuItem(
                                    text = { Text("Remove from group") },
                                    leadingIcon = { Icon(Icons.Rounded.FolderOff, null) },
                                    onClick = { onMoveToFolder(item, null); showFolderSelector = false }
                                )
                            }
                        }
                        items(folders) { folder ->
                            DropdownMenuItem(
                                text = { Text(folder.name) },
                                leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                                onClick = { onMoveToFolder(item, folder.id); showFolderSelector = false }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({ showFolderSelector = false }) { Text("Cancel") }
            }
        )
    }

}

// ── Pinned apps list ──────────────────────────────────────────────────────────
@Composable
private fun PinnedAppsList(
    items:    List<LauncherItemEntity>,
    modifier: Modifier,
    onLaunch: (LauncherItemEntity) -> Unit,
    onMove:   (Int, Int) -> Unit
) {
    val density = LocalDensity.current
    var measuredSlotHeightPx by remember { mutableFloatStateOf(with(density) { 84.dp.toPx() }) }
    val listSpacingPx        = with(density) { 8.dp.toPx() }

    var draggingKey  by remember { mutableStateOf<String?>(null) }
    var dragOffsetY  by remember { mutableFloatStateOf(0f) }

    val draggingIndex = remember(draggingKey, items) {
        draggingKey?.let { key -> items.indexOfFirst { it.componentKey == key } } ?: -1
    }
    val targetIndex = remember(draggingIndex, dragOffsetY, items) {
        if (draggingIndex < 0) -1
        else (draggingIndex + (dragOffsetY / measuredSlotHeightPx).roundToInt())
            .coerceIn(0, items.lastIndex)
    }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(
            items       = items,
            key         = { _, item -> item.componentKey },
            contentType = { _, _ -> "pinned_row" }
        ) { index, item ->
            val isDragging = item.componentKey == draggingKey

            val neighbourShift = when {
                draggingIndex < 0 -> 0f
                isDragging        -> 0f
                index in (minOf(draggingIndex, targetIndex)..maxOf(draggingIndex, targetIndex)) ->
                    if (targetIndex > draggingIndex) -measuredSlotHeightPx else measuredSlotHeightPx
                else -> 0f
            }

            val animatedNeighbourShift by animateFloatAsState(
                targetValue   = neighbourShift,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label         = "neighbour_shift_$index"
            )

            val latestIndex by rememberUpdatedState(index)

            Card(
                onClick  = { if (!isDragging) onLaunch(item) },
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffsetY else animatedNeighbourShift
                    }
                    .onSizeChanged { size ->
                        val h = size.height.toFloat() + listSpacingPx
                        if (h > 0f) measuredSlotHeightPx = h
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(
                        alpha = if (isDragging) 0.65f else 0.35f
                    )
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(12.dp, 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    AppIcon(item = item, disabled = false)
                    Text(
                        item.label,
                        modifier   = Modifier.weight(1f),
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        style      = MaterialTheme.typography.bodyLarge
                    )
                    Icon(
                        Icons.Rounded.DragHandle, "Drag",
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.pointerInput(item.componentKey) {
                            detectDragGestures(
                                onDragStart  = { draggingKey = item.componentKey; dragOffsetY = 0f },
                                onDrag       = { change, amount ->
                                    change.consume()
                                    dragOffsetY += amount.y
                                },
                                onDragEnd    = {
                                    val to = (latestIndex + (dragOffsetY / measuredSlotHeightPx)
                                        .roundToInt()).coerceIn(0, items.lastIndex)
                                    if (latestIndex != to) onMove(latestIndex, to)
                                    draggingKey = null
                                    dragOffsetY = 0f
                                },
                                onDragCancel = { draggingKey = null; dragOffsetY = 0f }
                            )
                        }
                    )
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
