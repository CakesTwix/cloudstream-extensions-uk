package com.lagradost.sync

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType

class CloudSyncProvider(val plugin: CloudSyncPlugin) : MainAPI() {
    override var name = "CloudSync"
    override var supportedTypes = setOf(TvType.Others)
    override var lang = "uk"
    override val hasMainPage = false

    override suspend fun getMainPage(page: Int, request: MainPageRequest) = null
}
