package com.zoomrx.hybridappsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fasterxml.jackson.databind.node.ObjectNode

class AppSyncBroadcastReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent != null && context != null) {
            when (intent.action) {
                "com.zoomrx.intent.action.FCM_HYBRID_APP_SYNC" -> {
                    val appSync = AppSync.getInstance()
                    if (!appSync.syncInProgress.compareAndSet(false,true)) {
                        val hybridNode =
                            appSync.objectMapper.readValue(intent.dataString, ObjectNode::class.java)
                        appSync.checkForAndHandleHybridUpdate(hybridNode)
                    }
                }
            }
        }
    }
}