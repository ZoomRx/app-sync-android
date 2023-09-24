package com.zoomrx.hybridappsync

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

class HybridZip @JsonCreator constructor (
    @JsonProperty("name") val name: String,
    @JsonProperty("integrityHash") var integrityHash: String,
    @JsonProperty("url") var url: String,
    @JsonProperty("downloadState") var downloadState: Int = DownloadState.NOT_STARTED
) {
    object DownloadState {
        const val NOT_STARTED = 0
        const val DOWNLOADING = 1
        const val DOWNLOAD_ERROR = 2
        const val DOWNLOADED = 3
    }
}