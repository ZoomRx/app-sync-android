package com.zoomrx.hybridappsync

import java.util.*

object AppSyncConfig {
    lateinit var SERVER_URL: String
    lateinit var PROJECT_IDENTIFIER: String
    var DISABLE_IN_APP_UPDATE = false
    var AUTO_HYBRID_UPDATE = false
    var DEVELOPMENT_MODE = false

    fun initialize(appSyncProperties: Properties) {
        SERVER_URL = appSyncProperties.getProperty("SERVER_URL")
        PROJECT_IDENTIFIER = appSyncProperties.getProperty("PROJECT_IDENTIFIER")
        DISABLE_IN_APP_UPDATE = appSyncProperties.getProperty("DISABLE_IN_APP_UPDATE").equals("true")
        AUTO_HYBRID_UPDATE = appSyncProperties.getProperty("AUTO_HYBRID_UPDATE").equals("true")
        DEVELOPMENT_MODE = appSyncProperties.getProperty("DEVELOPMENT_MODE").equals("true")
    }
}