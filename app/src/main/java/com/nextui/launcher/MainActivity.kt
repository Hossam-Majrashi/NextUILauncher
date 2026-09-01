package com.nextui.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextui.launcher.data.LauncherRepository
import com.nextui.launcher.ui.LauncherScreen
import com.nextui.launcher.ui.LauncherViewModel
import com.nextui.launcher.ui.OnboardingScreen

class MainActivity : ComponentActivity() {
    private lateinit var repository: LauncherRepository
    private val viewModel: LauncherViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LauncherViewModel(application, repository) as T
            }
        }
    }

    private var isActivityResumed = false

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Only jump to Home if the user taps Home while ALREADY in the launcher.
        // If returning from another app, stay on the exact page they were on (e.g. Page 5 in drawer).
        if (isActivityResumed &&
            intent.action == android.content.Intent.ACTION_MAIN && 
            intent.hasCategory(android.content.Intent.CATEGORY_HOME)) {
            viewModel.onHomePressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        repository = LauncherRepository(applicationContext)

        // Keep the splash screen on-screen until the ViewModel's loading state is false
        splashScreen.setKeepOnScreenCondition {
            viewModel.uiState.value.loading
        }

        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            
            MaterialTheme {
                if (state.showOnboarding) {
                    OnboardingScreen(onFinished = viewModel::completeOnboarding)
                } else {
                    LauncherScreen(
                        stateFlow = viewModel.uiState,
                        homeEvents = viewModel.homeEvents,
                        onSearchChange = viewModel::onSearchChange,
                        onSelectCategory = viewModel::onSelectCategory,
                        onTogglePinned = viewModel::togglePinned,
                        onMovePinned = viewModel::movePinned,
                        onSetCustomCategory = viewModel::setCustomCategory,
                        onLaunchApp = viewModel::launchApp,
                        onBackup = viewModel::backup,
                        onRestore = viewModel::restore,
                        onRemoveItem = viewModel::removeItem,
                        onOpenInStore = viewModel::openInStore,
                        onToggleHidden = viewModel::toggleHidden,
                        onCreateFolder = viewModel::createFolder,
                        onMoveToFolder = viewModel::moveToFolder,
                        onToggleHideAppsInFolders = viewModel::setHideAppsInFolders,
                        onRestoreMessageShown = viewModel::onRestoreMessageShown
                    )
                }
            }
        }
    }
}
