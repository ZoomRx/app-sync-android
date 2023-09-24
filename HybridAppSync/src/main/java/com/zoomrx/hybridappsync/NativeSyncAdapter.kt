package com.zoomrx.hybridappsync

import android.app.Activity
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.installStatus
import java.util.concurrent.atomic.AtomicReference

@JsonIgnoreProperties(ignoreUnknown = true)
class NativeSyncAdapter {

    object Constants {
        const val NATIVE_SYNC_FILE = "NativeSync"
        const val TIME_BETWEEN_OPTIONAL_ALERTS = 1000 * 60 * 60 * 24 * 14
    }

    @JsonIgnore
    private val appSync = AppSync.getInstance()

    @JsonIgnore
    var activity = AtomicReference<Activity?>(null)

    @JsonIgnore
    lateinit var appUpdateManager : AppUpdateManager
    var updateData: NativeUpdate? = null

    @JsonIgnore
    // Create a listener to track request state updates.
    private val listener = InstallStateUpdatedListener { state ->
        // (Optional) Provide a download progress bar.
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            unregisterUpdateStateListener()
            publishUpdateDownloadedEvent()
        }
        // Log state or install the update.
    }

    fun parseServerSyncData(nativeNode: JsonNode?): Boolean {
        val nativeReleaseNode = nativeNode?.path("release")
        if (nativeReleaseNode != null && !nativeReleaseNode.isMissingNode) {
            val upgradeType = if (nativeNode.path("unsupported_version")?.asBoolean() == true)
                NativeUpdate.MANDATORY
            else NativeUpdate.OPTIONAL
            val version = nativeReleaseNode.path("version").asText()
            if (updateData?.upgradeType != upgradeType || updateData?.version != version) {
                updateData = NativeUpdate(upgradeType, version)
            }
        }
        if (updateData?.userAcceptance != null && updateData?.upgradeType == NativeUpdate.OPTIONAL &&
            (System.currentTimeMillis() - (updateData?.lastOptionalAlertTimeStamp ?: 0L)) >
            Constants.TIME_BETWEEN_OPTIONAL_ALERTS) {
            updateData?.userAcceptance = null
        }
        saveThisInstance()
        return updateData != null && (updateData!!.upgradeType == HybridUpdate.MANDATORY ||
            (updateData!!.upgradeType == HybridUpdate.OPTIONAL && updateData?.userAcceptance != false))
    }

    fun customNativeUpdateDialogResponded(userAcceptance: Boolean) {
        updateData?.userAcceptance = userAcceptance
        updateData?.lastOptionalAlertTimeStamp = System.currentTimeMillis()
        saveThisInstance()
    }

    fun handleUpdate() {
        activity.get()?.let {

            // Returns an intent object that you use to check for an update.
            val appUpdateInfoTask = appUpdateManager.appUpdateInfo

            // Checks that the platform will allow the specified type of update.
            appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    // This example applies an immediate update. To apply a flexible update
                    // instead, pass in AppUpdateType.FLEXIBLE
                    // && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    // Using UpdateType received from in house Server
                    && updateData?.upgradeType == NativeUpdate.MANDATORY
                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    && appUpdateInfo.updatePriority() > 3) {
                    appUpdateManager.startUpdateFlow(
                        appUpdateInfo,
                        it,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                } else {
                    if (appUpdateInfo.installStatus == InstallStatus.INSTALLING) {
                        //Do nothing
                    } else {
                        if (appUpdateInfo.installStatus == InstallStatus.DOWNLOADED) {
                            publishUpdateDownloadedEvent()
                        } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                            // This example applies an immediate update. To apply a flexible update
                            // instead, pass in AppUpdateType.FLEXIBLE
                            // && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                            // Using UpdateType received from in house Server
                            && updateData?.upgradeType == NativeUpdate.OPTIONAL
                            && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                            && appUpdateInfo.updatePriority() > 1
                        ) {

                            // Before starting an update, register a listener for updates.
                            appUpdateManager.registerListener(listener)

                            // Start an update.
                            appUpdateManager.startUpdateFlow(
                                appUpdateInfo,
                                it,
                                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE)
                                    .build()
                            ).addOnCompleteListener { task ->
                                appSync.appSyncListeners.forEach {
                                    it.nativeUpdateDialogResponded(task.isSuccessful)
                                }
                            }
                        }
                        appSync.checkForAndHandleHybridUpdate()
                    }
                }
            }
        }
    }

    private fun unregisterUpdateStateListener() {
        appUpdateManager.unregisterListener(listener)
    }

    private fun publishUpdateDownloadedEvent() {
        appSync.appSyncListeners.forEach {
            it.onOptionalNativeUpdateDownloaded()
        }
    }

    fun popupSnackbarToCompleteUpdate() {
        activity.get()?.let {
            Snackbar.make(
                it.findViewById(R.id.action_bar_activity_content),
                "An update has just been downloaded.",
                Snackbar.LENGTH_INDEFINITE
            ).apply {
                setAction("RESTART") {
                    appUpdateManager.completeUpdate()
                }
                setActionTextColor(it.getColor(R.color.design_default_color_primary))
                show()
            }
        }
    }

    private fun saveThisInstance() {
        appSync.saveInstance(this, Constants.NATIVE_SYNC_FILE)
    }
}