package com.zoomrx.hybridappsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.zoomrx.filetransfer.DownloadContext
import com.zoomrx.filetransfer.FileTransferHandler
import com.zoomrx.logger.Logger
import kotlinx.coroutines.*
import java.lang.IllegalStateException

class HybridZipDownloadWorker(context: Context, workerParams: WorkerParameters) : ListenableWorker(context,
    workerParams
) {
    private val appSync = AppSync.getInstance()

    override fun getForegroundInfoAsync(): ListenableFuture<ForegroundInfo> {
        return CallbackToFutureAdapter.getFuture {
            it.set(ForegroundInfo(1, createNotification()))
        }
    }

    override fun startWork(): ListenableFuture<Result> {
        try {
            setForegroundAsync(foregroundInfoAsync.get())
        } catch (exception: IllegalStateException) {
            Logger.warn(exception)
        }
        return CallbackToFutureAdapter.getFuture { completer ->
            CoroutineScope(Dispatchers.IO).launch {
                //Parse the input and set the downloadState of hybridZips accordingly
                val hybridUpdateData = appSync.hybridSyncAdapter.updateData
                if (hybridUpdateData != null) {
                    hybridUpdateData.downloadedZipList.clear()
                    hybridUpdateData.failedZipList.clear()
                    val resolveWork = {
                        appSync.hybridSyncAdapter.saveThisInstance()
                        if (!appSync.hybridSyncAdapter.isAnyDownloadPending()) {
                            if (appSync.hybridSyncAdapter.areAllFilesDownloaded()) {
                                appSync.hybridSyncAdapter.processWorkerCompletion(HybridSyncAdapter.Constants.WORKER_SUCCESS)
                                completer.set(Result.success())
                            } else {
                                appSync.hybridSyncAdapter.processWorkerCompletion(HybridSyncAdapter.Constants.WORKER_FAILURE)
                                completer.set(Result.failure())
                            }
                        }
                    }
                    val hybridZips = appSync.hybridSyncAdapter.hybridZips
                    hybridZips.forEach {
                        if (it.downloadState != HybridZip.DownloadState.DOWNLOADED) {
                            // source old: baseDownloadUrl + "/${it.name}"
                            FileTransferHandler.enqueueDownload(
                                DownloadContext(
                                    it.url,
                                    applicationContext.filesDir.toString() + "/hybridApp_new/${it.name}",
                                    null,
                                    null,
                                    { _ ->
                                        it.downloadState = HybridZip.DownloadState.DOWNLOADED
                                        hybridUpdateData.downloadedZipList.add(it.name.substring(0, it.name.indexOf(".zip")))
                                        Logger.info("Downloaded ${it.name}")
                                        resolveWork()
                                    },
                                    { error ->
                                        it.downloadState = HybridZip.DownloadState.DOWNLOAD_ERROR
                                        hybridUpdateData.failedZipList.add(it.name.substring(0, it.name.indexOf(".zip")))
                                        Logger.error("Failed downloading ${it.name}. Error: $error")
                                        resolveWork()
                                    },
                                    false
                                ),
                                true
                            )
                            it.downloadState = HybridZip.DownloadState.DOWNLOADING
                            appSync.hybridSyncAdapter.saveThisInstance()
                        }
                    }
                } else {
                    appSync.hybridSyncAdapter.processWorkerCompletion(HybridSyncAdapter.Constants.WORKER_SUCCESS)
                    completer.set(Result.success())
                }
            }
        }
    }

    override fun onStopped() {
        super.onStopped()
        appSync.hybridSyncAdapter.processWorkerCompletion(HybridSyncAdapter.Constants.WORKER_STOPPED)
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "APP_SYNC_CHANNEL"
            val descriptionText = "This channel is used for App sync using WorkManager APIs"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(AppSync.Constants.CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(applicationContext, AppSync.Constants.CHANNEL_ID)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("APP SYNC")
            .setContentText("Syncing app data with server")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }
}