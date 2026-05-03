package com.nextui.launcher

import android.app.Application
import com.nextui.launcher.data.IconCache

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        IconCache.init(this)
    }
}
