package com.zoomrx.hybridappsync

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.node.ArrayNode

class HybridUpdate @JsonCreator constructor(
    @JsonProperty("upgradeType") val upgradeType: Int,
    @JsonProperty("version") val version: String,
    @JsonProperty("zips") val zips: ArrayNode
) {
    companion object UpgradeType {
        const val OPTIONAL = 1
        const val MANDATORY = 2
    }
    @JsonProperty("userAcceptance")
    var userAcceptance: Boolean? = null

    @JsonProperty("lastOptionalAlertTimeStamp")
    var lastOptionalAlertTimeStamp = 0L

    var downloadedZipList = arrayListOf<String>()
    var failedZipList = arrayListOf<String>()
    var downloadDuration = 0L
    var result: Boolean = false
}