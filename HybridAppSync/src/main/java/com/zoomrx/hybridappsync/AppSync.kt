package com.zoomrx.hybridappsync

import android.app.Activity
import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.example.coremodule.AppContainer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.android.gms.appset.AppSet
import com.google.android.gms.tasks.RuntimeExecutionException
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.firebase.messaging.FirebaseMessaging
import com.zoomrx.filetransfer.DownloadContext
import com.zoomrx.filetransfer.FileTransferHandler
import com.zoomrx.logger.Logger
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AppSync private constructor(val appContext: Application) {

    companion object {
        private var instance: AppSync? = null

        @Synchronized
        @JvmStatic
        fun initialize(appContext: Application) {
            if (instance == null) {
                instance = AppSync(appContext)
            }
        }

        @Synchronized
        @JvmStatic
        fun getInstance(): AppSync {
            return instance as AppSync
        }
    }

    object Constants {
        const val CHANNEL_ID = "1"
    }

    private val appContainer: AppContainer = AppContainer.getInstance()
    private val okHttpClient: OkHttpClient = appContainer.okHttpClient
    val objectMapper: ObjectMapper = appContainer.objectMapper
    private var lastSyncTimeStamp = 0L
    lateinit var hybridSyncAdapter: HybridSyncAdapter
    var nativeSyncAdapter: NativeSyncAdapter? = null
    var appSyncListeners: ArrayList<AppSyncListener> = arrayListOf()
    var syncInProgress = AtomicBoolean(false)
    private var isFirstSyncInSession = AtomicBoolean(false)

    private val activityLifecycleCallback = object : Application.ActivityLifecycleCallbacks {

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            //Do nothing
        }

        override fun onActivityStarted(activity: Activity) {
            if (nativeSyncAdapter == null) {
                constructNativeSyncAdapter()
            }
            nativeSyncAdapter!!.activity.set(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            //Do nothing
        }

        override fun onActivityPaused(activity: Activity) {
            //Do nothing
        }

        override fun onActivityStopped(activity: Activity) {
            //Do nothing
            if (nativeSyncAdapter!!.activity.get() == activity) {
                nativeSyncAdapter!!.activity.set(null)
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            //Do nothing
        }

        override fun onActivityDestroyed(activity: Activity) {
            //Do nothing
            appSyncListeners.forEach {
                if (it.context == activity) {
                    unRegisterEventListener(it)
                }
            }
        }
    }

    init {
        appContext.registerActivityLifecycleCallbacks(activityLifecycleCallback)
    }

    private fun constructNativeSyncAdapter() {
        retrieveInstance<NativeSyncAdapter>(NativeSyncAdapter.Constants.NATIVE_SYNC_FILE)?.let {
            nativeSyncAdapter = it
        } ?: run {
            nativeSyncAdapter = NativeSyncAdapter()
        }
        nativeSyncAdapter!!.appUpdateManager = AppUpdateManagerFactory.create(appContext)
    }

    @Synchronized
    fun registerEventListener(appSyncListener: AppSyncListener) {
        appSyncListeners.add(appSyncListener)
    }

    @Synchronized
    fun unRegisterEventListener(appSyncListener: AppSyncListener) {
        appSyncListeners.remove(appSyncListener)
    }

    private suspend fun getDeviceIdentifier(): String? {
        return try {
            AppSet.getClient(appContext).appSetIdInfo.await().id
        } catch (exception: RuntimeExecutionException) {
            null
        }
    }

    private suspend fun getFirebaseMessagingToken(): String? {
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (exception: RuntimeExecutionException) {
            null
        }
    }

    suspend fun syncWithServer(callback: ServerResponseCallback) {
        if (!syncInProgress.compareAndSet(false,true)) {
            callback.onFailure(ServerResponseCallback.ErrorType.IN_PROGRESS)
            return
        }

        if (AppSyncConfig.DEVELOPMENT_MODE) {
            if (isFirstSyncInSession.compareAndSet(false, true)) {
                FileTransferHandler.enqueueDownload(
                    DownloadContext(
                        AppSyncConfig.SERVER_URL,
                        appContext.filesDir.toString() + "/hybridApp.zip",
                        null,
                        null,
                        {
                            val zipUtil = ZipUtil.getInstance(appContext)
                            val hybridAppDirPath = appContext.filesDir.toString() + "/hybridApp/"
                            File(hybridAppDirPath).deleteRecursively()
                            zipUtil.unzip(
                                appContext.filesDir.toString() + "/hybridApp.zip",
                                hybridAppDirPath,
                                false
                            )
                            callback.onSuccess(null, null)
                        },
                        {
                            callback.onFailure(ServerResponseCallback.ErrorType.OTHER)
                        },
                        false
                    )
                )
            }
        } else {
            try {
                val pInfo: PackageInfo =
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                val version = pInfo.versionName
                val requestBody = objectMapper.createObjectNode()
                    .put("last_sync_timeStamp", lastSyncTimeStamp.toString())
                    .put("hybrid_version", hybridSyncAdapter.version)
                    .put("native_version", version)
                    .put("platform", "Android")
                    .put("platform_version", Build.VERSION.RELEASE)
                    .put("device_identifier", getDeviceIdentifier())
                    .put("notification_token", getFirebaseMessagingToken())
                    .toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("${AppSyncConfig.SERVER_URL}/app_sync/check_for_update")
                    .addHeader("project-identifier", AppSyncConfig.PROJECT_IDENTIFIER)
                    .post(requestBody)
                    .build()
                okHttpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        e.printStackTrace()
                        Logger.error("App sync request failed - ${e.message}")
                        callback.onFailure(ServerResponseCallback.ErrorType.OTHER)
                        syncInProgress.lazySet(false)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!response.isSuccessful) {
                                Logger.error("App sync request failed - ${response.body.toString()}")
                                callback.onFailure(ServerResponseCallback.ErrorType.OTHER)
                            }
                            else {
                                val responseBody = response.body?.string()
                                if (responseBody != null) {
                                    Logger.debug("Response: $responseBody")
                                }
                                val responseNode = objectMapper.readValue(responseBody ?: "{}", ObjectNode::class.java)
                                val serverUnderMaintenance = responseNode.get("maintenance_mode").asBoolean()
                                val hybridNode = responseNode.get("hybrid")
                                val nativeNode = responseNode.get("native")
                                val hybridReleaseNode = hybridNode.get("release")
                                val nativeReleaseNode = nativeNode.get("release")
                                val unsupportedVersion =
                                    (hybridNode?.get("unsupported_version")?.asBoolean() == true && hybridReleaseNode == null) ||
                                            (nativeNode?.get("unsupported_version")?.asBoolean() == true && nativeReleaseNode == null)
                                when {
                                    serverUnderMaintenance -> {
                                        callback.onFailure(ServerResponseCallback.ErrorType.SERVER_MAINTENANCE)
                                    }
                                    unsupportedVersion -> {
                                        callback.onFailure(ServerResponseCallback.ErrorType.UNSUPPORTED_VERSION)
                                    }
                                    else -> {
                                        val nativeUpdateAvailable = nativeSyncAdapter?.parseServerSyncData(nativeNode)
                                        hybridSyncAdapter.parseServerSyncData(hybridNode)
                                        if (!AppSyncConfig.DISABLE_IN_APP_UPDATE && nativeUpdateAvailable == true && nativeSyncAdapter != null) {
                                            nativeSyncAdapter!!.handleUpdate()
                                        } else if (AppSyncConfig.AUTO_HYBRID_UPDATE) {
                                            checkForAndHandleHybridUpdate()
                                        }
                                        callback.onSuccess(nativeSyncAdapter?.updateData, hybridSyncAdapter.updateData)
                                        if (nativeUpdateAvailable == true && !AppSyncConfig.DISABLE_IN_APP_UPDATE) {
                                            return@use
                                        }
                                    }
                                }
                            }
                            syncInProgress.lazySet(false)
                        }
                    }
                })
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    fun checkForAndHandleHybridUpdate(hybridNode: JsonNode? = null) {
        syncInProgress.set(true)
        var isUpdateAvailable = hybridSyncAdapter.isUpdateAvailable()
        if (hybridNode != null) {
            isUpdateAvailable = hybridSyncAdapter.parseServerSyncData(hybridNode)
        }
        if (isUpdateAvailable) {
            Logger.info("Starting hybrid update")
            hybridSyncAdapter.handleUpdate()
        } else {
            Logger.info("Hybrid update not available")
            hybridSyncAdapter.handleAfterSyncEvent()
        }
    }

    fun saveInstance(instance: Any, filename: String) {
        val file = File(appContext.filesDir.toString() + File.separator + filename)
        file.createNewFile()
        objectMapper.writeValue(file, instance)
    }

    inline fun <reified T: Any> retrieveInstance(filename: String): T? {
        val file = File(appContext.filesDir.toString() + File.separator + filename)
        return if (file.exists() && file.length() != 0L) {
            objectMapper.readValue(file, T::class.java)
        } else null
    }
}