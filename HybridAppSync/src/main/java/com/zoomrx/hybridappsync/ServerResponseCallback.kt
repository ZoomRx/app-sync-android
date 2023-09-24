package com.zoomrx.hybridappsync

interface ServerResponseCallback {
    enum class ErrorType {
        SERVER_MAINTENANCE, UNSUPPORTED_VERSION, IN_PROGRESS, OTHER
    }
    fun onSuccess(nativeUpdate: NativeUpdate?, hybridUpdate: HybridUpdate?)

    fun onFailure(errorType: ErrorType)
}