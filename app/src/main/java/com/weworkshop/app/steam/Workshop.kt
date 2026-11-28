package com.weworkshop.app.steam

import com.weworkshop.app.App
import com.weworkshop.app.R
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_AreFilesInSubscriptionList_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_Subscribe_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_Unsubscribe_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetDetails_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.PublishedFileDetails
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

data class WorkshopItem(
    val id: Long,
    val title: String,
    val previewUrl: String,
    val fileSize: Long,
    val tags: List<String>,
    val description: String,
    val subscriptions: Int,
    val favorited: Int,
    val timeUpdated: Long,
    val voteScore: Float,
    val votesUp: Int,
    val extraPreviews: List<String> = emptyList(),
    /** Depot manifest id of the item's content (most Wallpaper Engine items). */
    val hcontentFile: Long = 0,
    /** Direct download URL for legacy single-file items; usually empty. */
    val fileUrl: String = "",
    val fileName: String = "",
    /** Built from the local download entry only (no Steam data yet): can't be re-downloaded from this. */
    val partial: Boolean = false,
) {
    val type: String? get() = tags.firstOrNull { it in Workshop.TYPE_TAGS }
    val ageRating: String? get() = tags.firstOrNull { it in Workshop.AGE_TAGS }
    val pageUrl: String get() = "https://steamcommunity.com/sharedfiles/filedetails/?id=$id"
}

data class WorkshopPage(val items: List<WorkshopItem>, val nextCursor: String?, val total: Int)

enum class SortOrder(@androidx.annotation.StringRes val label: Int, val queryType: Int, val days: Int = 0) {
    /** Text-search relevance; only offered while a search is active. */
    RELEVANCE(R.string.sort_relevance, 12),
    TREND_WEEK(R.string.sort_trend_week, 3, 7),
    TREND_MONTH(R.string.sort_trend_month, 3, 30),
    TOP_RATED(R.string.sort_top_rated, 0),
    MOST_SUBSCRIBED(R.string.sort_most_subscribed, 9),
    NEWEST(R.string.sort_newest, 1),
    UPDATED(R.string.sort_updated, 21),
}

object Workshop {
    val TYPE_TAGS = listOf("Scene", "Video", "Web", "Application", "Preset")
    val AGE_TAGS = listOf("Everyone", "Questionable", "Mature")

    /** Display-name resources for the type tags. */
    val TYPE_LABELS = mapOf(
        "Scene" to R.string.type_scene,
        "Video" to R.string.type_video,
        "Web" to R.string.type_web,
        "Application" to R.string.type_application,
        "Preset" to R.string.type_preset,
    )

    /** Commonly used Wallpaper Engine genre tags (as used on the workshop). */
    val GENRE_TAGS = listOf(
        "Anime", "Landscape", "Nature", "Game", "Abstract", "Sci-Fi", "Fantasy", "Girls",
        "Cyberpunk", "Pixel art", "Vehicle", "Relaxing", "Music", "Animal", "Technology", "Space",
    )

    /**
     * Workshop resolution tags, phone-friendly portrait ones first. Names must match Steam's
     * tag names exactly (taken from the Wallpaper Engine workshop's tag list).
     */
    val RESOLUTION_TAGS = listOf(
        "Portrait 1080 x 1920", "Portrait 1440 x 2560", "Portrait 2160 x 3840", "Portrait 720 x 1280",
        "Portrait Standard Definition",
        "3840 x 2160", "2560 x 1440", "1920 x 1080", "1366 x 768", "1280 x 720", "Standard Definition",
        "Ultrawide 3440 x 1440", "Ultrawide 2560 x 1080", "Ultrawide Standard Definition",
        "Dual 7680 x 2160", "Dual 5120 x 1440", "Dual 3840 x 1080", "Dual Standard Definition",
        "Triple 11520 x 2160", "Triple 7680 x 1440", "Triple 5760 x 1080", "Triple 4096 x 768",
        "Triple Standard Definition",
        "Dynamic resolution", "Other resolution",
    )

    /** Items seen in listings, so the detail screen can render instantly. */
    val cache = java.util.concurrent.ConcurrentHashMap<Long, WorkshopItem>()

    private const val TIMEOUT_MS = 30_000L

    /**
     * Sending a unified message writes to the CM socket on the calling thread (synchronously on
     * the TCP transport), so always issue RPCs from IO — callers are often on Main.
     */
    private suspend fun <T> rpc(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { withTimeout(TIMEOUT_MS) { block() } }

    private val service: PublishedFile by lazy {
        SteamSession.unified.createService(PublishedFile::class.java)
    }

    suspend fun query(
        sort: SortOrder,
        search: String,
        typeTag: String?,
        genreTag: String?,
        resolutionTag: String?,
        allowedAges: Set<String>,
        cursor: String?,
        perPage: Int = 30,
        includePresets: Boolean = true,
    ): WorkshopPage {
        SteamSession.awaitLoggedIn()
        val req = CPublishedFile_QueryFiles_Request.newBuilder().apply {
            appid = SteamSession.WE_APP_ID
            creatorAppid = SteamSession.WE_APP_ID
            numperpage = perPage
            this.cursor = cursor ?: "*"
            // search_text filters any ranking, so search results can be sorted too.
            if (search.isNotBlank()) searchText = search.trim()
            queryType = sort.queryType
            if (sort.days > 0) {
                days = sort.days
                includeRecentVotesOnly = true
            }
            typeTag?.let { addRequiredtags(it) }
            genreTag?.let { addRequiredtags(it) }
            resolutionTag?.let { addRequiredtags(it) }
            matchAllTags = true
            AGE_TAGS.filter { it !in allowedAges }.forEach { addExcludedtags(it) }
            if (!includePresets && typeTag == null) addExcludedtags("Preset")
            returnTags = true
            returnVoteData = true
            returnShortDescription = true
            returnPreviews = false
            returnMetadata = false
            stripDescriptionBbcode = true
        }.build()

        val resp = rpc { service.queryFiles(req).await() }
        if (resp.result != EResult.OK) throw IOException(App.str(R.string.err_query, resp.result.toString()))
        val body = resp.body
        val next = body.nextCursor.takeIf { it.isNotEmpty() && it != cursor }
        val items = body.publishedfiledetailsList.filter { it.result == 1 }.map { it.toItem() }
        items.forEach { cache.putIfAbsent(it.id, it) }
        return WorkshopPage(
            items = items,
            nextCursor = next,
            total = body.total,
        )
    }

    /**
     * Items the signed-in account is subscribed to (page is 1-based). Only lists them; nothing
     * is downloaded. Sorted by subscription date when Steam accepts that sort, else its default.
     */
    suspend fun subscribed(page: Int, perPage: Int = 30): WorkshopPage {
        SteamSession.awaitLoggedIn()
        val steamId = SteamSession.client.steamID?.convertToUInt64()
            ?: throw IOException(App.str(R.string.err_query, "no SteamID"))
        fun build(sort: String?) = CPublishedFile_GetUserFiles_Request.newBuilder().apply {
            steamid = steamId
            appid = SteamSession.WE_APP_ID
            // The CM accepts "myfiles", "mysubscriptions" and "myfavorites"; "subscribed" (the
            // Web API's name) is rejected with InvalidParam.
            type = "mysubscriptions"
            sort?.let { sortmethod = it }
            this.page = page
            numperpage = perPage
            returnTags = true
            returnVoteData = true
            returnShortDescription = true
            stripDescriptionBbcode = true
        }.build()

        var resp = rpc { service.getUserFiles(build("subscriptiondate")).await() }
        if (resp.result != EResult.OK) resp = rpc { service.getUserFiles(build(null)).await() }
        if (resp.result != EResult.OK) throw IOException(App.str(R.string.err_query, resp.result.toString()))
        val body = resp.body
        val details = body.publishedfiledetailsList
        val items = details.filter { it.result == 1 }.map { it.toItem() }
        items.forEach { cache.putIfAbsent(it.id, it) }
        val more = details.size >= perPage && page * perPage < body.total
        return WorkshopPage(items = items, nextCursor = if (more) (page + 1).toString() else null, total = body.total)
    }

    /** Bumped after every subscribe/unsubscribe so the subscriptions page knows to reload. */
    val subscriptionsVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Whether the signed-in account is subscribed to [id]. */
    suspend fun isSubscribed(id: Long): Boolean {
        SteamSession.awaitLoggedIn()
        val req = CPublishedFile_AreFilesInSubscriptionList_Request.newBuilder()
            .setAppid(SteamSession.WE_APP_ID)
            .addPublishedfileids(id)
            .build()
        val resp = rpc { service.areFilesInSubscriptionList(req).await() }
        if (resp.result != EResult.OK) throw IOException(App.str(R.string.err_query, resp.result.toString()))
        return resp.body.filesList.any { it.publishedfileid == id && it.inlist }
    }

    suspend fun setSubscribed(id: Long, subscribe: Boolean) {
        SteamSession.awaitLoggedIn()
        val result = if (subscribe) {
            val req = CPublishedFile_Subscribe_Request.newBuilder()
                .setPublishedfileid(id).setAppid(SteamSession.WE_APP_ID).setNotifyClient(false).build()
            rpc { service.subscribe(req).await() }.result
        } else {
            val req = CPublishedFile_Unsubscribe_Request.newBuilder()
                .setPublishedfileid(id).setAppid(SteamSession.WE_APP_ID).setNotifyClient(false).build()
            rpc { service.unsubscribe(req).await() }.result
        }
        if (result != EResult.OK) throw IOException(App.str(R.string.err_query, result.toString()))
        subscriptionsVersion.value++
    }

    suspend fun details(id: Long): WorkshopItem {
        SteamSession.awaitLoggedIn()
        val req = CPublishedFile_GetDetails_Request.newBuilder()
            .addPublishedfileids(id)
            .setIncludetags(true)
            .setIncludevotes(true)
            .setIncludeadditionalpreviews(true)
            .setStripDescriptionBbcode(true)
            .build()
        val resp = rpc { service.getDetails(req).await() }
        if (resp.result != EResult.OK) throw IOException(App.str(R.string.err_details, resp.result.toString()))
        val d = resp.body.publishedfiledetailsList.firstOrNull()
            ?: throw IOException(App.str(R.string.err_not_found))
        if (d.result != 1) throw IOException(App.str(R.string.err_unavailable, EResult.from(d.result).toString()))
        return d.toItem().also { cache[id] = it }
    }

    private fun PublishedFileDetails.toItem() = WorkshopItem(
        id = publishedfileid,
        title = title.ifBlank { "#$publishedfileid" },
        previewUrl = previewUrl,
        fileSize = fileSize,
        tags = tagsList.map { it.tag },
        description = fileDescription.ifBlank { shortDescription },
        subscriptions = subscriptions,
        favorited = favorited,
        timeUpdated = timeUpdated.toLong(),
        voteScore = voteData.score,
        votesUp = voteData.votesUp,
        // preview_type 0 == image
        extraPreviews = previewsList.filter { it.previewType == 0 && it.url.isNotEmpty() }.map { it.url },
        hcontentFile = hcontentFile,
        fileUrl = fileUrl,
        fileName = filename,
    )
}
