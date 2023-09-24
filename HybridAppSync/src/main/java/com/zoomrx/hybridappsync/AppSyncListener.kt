package com.zoomrx.hybridappsync

import android.content.Context

abstract class AppSyncListener(val context: Context) {

    fun nativeUpdateDialogResponded(userAcceptance: Boolean) {}

    fun requireUserConsentForHybridUpdate() {
        val appSync = AppSync.getInstance()
        appSync.hybridSyncAdapter.afterUserConsentOnOptionalUpgrade(true)
    }

    fun onOptionalNativeUpdateDownloaded() {
        val appSync = AppSync.getInstance()
        appSync.nativeSyncAdapter?.popupSnackbarToCompleteUpdate()
    }

    abstract fun afterSyncWithServer(syncSuccess: Boolean, hybridUpdate: HybridUpdate? = null)
}