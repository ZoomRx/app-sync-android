package com.zoomrx.hybridappsync

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import androidx.work.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.zoomrx.logger.Logger
import java.io.File

@JsonIgnoreProperties(ignoreUnknown = true)
class HybridSyncAdapter {

    @JsonIgnore
    val appSync = AppSync.getInstance()

    object Constants {
        const val APP_INFO = "app_info"
        const val WORKER_TAG = "HYBRID_ZIP_DOWNLOAD"
        const val WORKER_SUCCESS = 1
        const val WORKER_FAILURE = 2
        const val WORKER_STOPPED = 3
        const val HYBRID_SYNC_FILE = "HybridSync"
        const val TIME_BETWEEN_OPTIONAL_ALERTS = 1000 * 60 * 60 * 24 * 14
    }

    private var downloadStartTime = 0L
    private var downloadStopTime = 0L
    var version: String? = null
    val hybridZips = arrayListOf<HybridZip>()
    var updateData: HybridUpdate? = null

    @JsonIgnore
    lateinit var appContext: Application

    @JsonIgnore
    lateinit var zipUtil: ZipUtil

    fun initialize(appContext: Application) {
        this.appContext = appContext
        zipUtil = ZipUtil.getInstance(appContext)
        appSync.hybridSyncAdapter = this
        extractAssetsZipToFilesDir()
    }

    fun parseServerSyncData(hybridNode: JsonNode?): Boolean {
        val hybridReleaseNode = hybridNode?.path("release")
        if (hybridReleaseNode != null && !hybridReleaseNode.isMissingNode) {
            val upgradeType = if (hybridNode.path("unsupported_version").asBoolean())
                HybridUpdate.MANDATORY else HybridUpdate.OPTIONAL
            updateData = HybridUpdate(
                upgradeType,
                hybridReleaseNode.path("version").asText(),
                hybridReleaseNode.withArray("zips") as ArrayNode
            )
            processZipFilesDataFromServer()
            if (areAllFilesDownloaded() && updateData?.version != version) {
                if (updateData != null) {
                    version = updateData!!.version
                    updateData = null
                }
            }
            saveThisInstance()
        }
        return isUpdateAvailable()
    }

    private fun extractAssetsZipToFilesDir() {
        val settings: SharedPreferences =
            appContext.getSharedPreferences(Constants.APP_INFO, Context.MODE_PRIVATE)
        val savedAppVersion = settings.getString("app_version", null)
        val pInfo: PackageInfo =
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val currentAppVersion = pInfo.versionName
        if (savedAppVersion == null || savedAppVersion != currentAppVersion) {
            val zipUtil = ZipUtil.getInstance(appContext)
            val hybridAppDirPath = appContext.filesDir.toString() + "/hybridApp/"
            File(hybridAppDirPath).deleteRecursively()
            version = null
            saveThisInstance()
            zipUtil.unzip(
                "hybridApp.zip",
                hybridAppDirPath,
                true
            )
            settings.edit().putString("app_version", currentAppVersion).apply()
        }
    }

    fun isUpdateAvailable(): Boolean {
        return if (!areAllFilesDownloaded()) {
            updateData!!.upgradeType == HybridUpdate.MANDATORY ||
                    (updateData!!.upgradeType == HybridUpdate.OPTIONAL && (
                            updateData?.userAcceptance != false ||
                                    (System.currentTimeMillis() - (updateData?.lastOptionalAlertTimeStamp ?: 0L)) >
                                    Constants.TIME_BETWEEN_OPTIONAL_ALERTS
                            ))
        } else false
    }

    private fun processZipFilesDataFromServer() {
        updateData?.zips?.forEach {
            val hybridZip = hybridZips.find { hybridZip ->
                hybridZip.name == it.path("name").asText()
            }

            if (hybridZip != null) {
                if (hybridZip.integrityHash != it.path("content_hash").asText()) {
                    Logger.debug("Old zip (${it.path("name")}) changed")
                    hybridZip.integrityHash = it.path("content_hash").asText()
                    hybridZip.downloadState = HybridZip.DownloadState.NOT_STARTED
                }
                hybridZip.url = it.path("url").asText()
            } else {
                Logger.debug("New zip (${it.path("name")}) is available")
                hybridZips.add(HybridZip(
                    it.path("name").asText(),
                    it.path("content_hash").asText(),
                    it.path("url").asText(),
                    HybridZip.DownloadState.NOT_STARTED
                ))
            }
        }
    }

    fun handleUpdate() {
        if (updateData?.upgradeType == HybridUpdate.OPTIONAL) {
            appSync.appSyncListeners.forEach {
                it.requireUserConsentForHybridUpdate()
            }
        } else {
            startHybridFilesDownload()
        }
    }

    fun afterUserConsentOnOptionalUpgrade(userAcceptance: Boolean) {
        updateData?.userAcceptance = userAcceptance
        saveThisInstance()
        if (userAcceptance) {
            startHybridFilesDownload()
        } else {
            handleAfterSyncEvent()
        }
    }

    private fun startHybridFilesDownload() {
        Logger.info("Starting hybrid file download")
        downloadStartTime = System.currentTimeMillis()
        val workRequest = OneTimeWorkRequestBuilder<HybridZipDownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(appContext).beginUniqueWork(
            Constants.WORKER_TAG,
            ExistingWorkPolicy.KEEP,
            workRequest
        ).enqueue()
    }

    fun processWorkerCompletion(
        workerCompletionState: Int
    ) {
        downloadStopTime = System.currentTimeMillis()
        when (workerCompletionState) {
            Constants.WORKER_SUCCESS -> {
                Logger.info("Hybrid zip download process successful")
                val downloadedFolder = File(appContext.filesDir.toString() + "/hybridApp_new")
                val destinationPath = appContext.filesDir.toString() + "/hybridApp/"
                val destinationFolder = File(destinationPath)
                if (!destinationFolder.exists()) {
                    destinationFolder.mkdir()
                }
                downloadedFolder.listFiles()?.forEach {
                    zipUtil.unzip(it.absolutePath, destinationPath, false)
                }
                version = updateData!!.version
                updateData!!.result = true
                saveThisInstance()
            }
            Constants.WORKER_FAILURE -> {
                Logger.info("Hybrid zip download process failed")
                updateData!!.result = false
            }
            else -> {
                Logger.info("Hybrid zip download process failed")
                updateData!!.result = true
            }
        }
        handleAfterSyncEvent()
        saveThisInstance()
    }

    fun isAnyDownloadPending(): Boolean {
        return hybridZips.any {
            it.downloadState == HybridZip.DownloadState.DOWNLOADING
        }
    }

    fun areAllFilesDownloaded(): Boolean {
        return hybridZips.all {
            it.downloadState == HybridZip.DownloadState.DOWNLOADED
        }
    }

    fun handleAfterSyncEvent() {
        val hybridUpdateData = updateData
        if (hybridUpdateData != null) {
            //Hybrid update available
            if (hybridUpdateData.upgradeType == HybridUpdate.OPTIONAL && hybridUpdateData.userAcceptance == false) {
                //User declined optional upgrade
            } else {
                hybridUpdateData.downloadDuration = (downloadStopTime - downloadStartTime) / 1000
                appSync.appSyncListeners.forEach {
                    it.afterSyncWithServer(hybridUpdateData.result, updateData)
                }
                if (hybridUpdateData.result) {
                    updateData = null
                }
            }
        } else {
            //No hybrid update available
            appSync.appSyncListeners.forEach {
                it.afterSyncWithServer(true)
            }
        }
        appSync.syncInProgress.lazySet(false)
    }

    fun saveThisInstance() {
        appSync.saveInstance(this, Constants.HYBRID_SYNC_FILE)
    }
}