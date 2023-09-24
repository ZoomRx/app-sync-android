package com.zoomrx.hybridappsync

import android.app.Application
import android.content.Context
import androidx.startup.Initializer

class HybridSyncAdapterInitializer: Initializer<HybridSyncAdapter> {
    override fun create(context: Context): HybridSyncAdapter {
        val appSync = AppSync.getInstance()
        val hybridSyncAdapter = appSync.retrieveInstance(HybridSyncAdapter.Constants.HYBRID_SYNC_FILE) ?: HybridSyncAdapter()
        hybridSyncAdapter.initialize(context.applicationContext as Application)
        return hybridSyncAdapter
    }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> {
        return mutableListOf(AppSyncInitializer::class.java)
    }
}