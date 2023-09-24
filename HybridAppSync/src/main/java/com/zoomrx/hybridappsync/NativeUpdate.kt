package com.zoomrx.hybridappsync

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

class NativeUpdate @JsonCreator constructor(
    @JsonProperty("upgradeType") val upgradeType: Int,
    @JsonProperty("version") var version: String,
) {
    companion object UpgradeType {
        const val OPTIONAL = 1
        const val MANDATORY = 2
    }

    @JsonProperty("userAcceptance")
    var userAcceptance: Boolean? = null

    @JsonProperty("lastOptionalAlertTimeStamp")
    var lastOptionalAlertTimeStamp = 0L
}