package com.nextui.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InstalledAppsDataSource(private val context: Context) {
    suspend fun load(): List<LauncherItemEntity> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else 0

        pm.queryIntentActivities(intent, flags).mapIndexed { index, info ->
            LauncherItemEntity(
                id = "${info.activityInfo.packageName}/${info.activityInfo.name}",
                label = info.loadLabel(pm).toString(),
                packageName = info.activityInfo.packageName,
                className = info.activityInfo.name,
                isInstalled = true,
                sortOrder = index
            )
        }
    }
}
