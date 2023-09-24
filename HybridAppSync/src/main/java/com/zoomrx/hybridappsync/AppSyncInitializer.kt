package com.zoomrx.hybridappsync

import android.app.Application
import android.content.Context
import androidx.startup.Initializer
import com.example.coremodule.AppContainerInitializer
import java.util.*

class AppSyncInitializer : Initializer<AppSync> {
    override fun create(context: Context): AppSync {
        val appSyncProperties = Properties()
        appSyncProperties.load(context.assets.open("app_sync.properties"))
        AppSyncConfig.initialize(appSyncProperties)
        AppSync.initialize(context as Application)
        return AppSync.getInstance()
    }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> {
        return mutableListOf(AppContainerInitializer::class.java)
    }

}