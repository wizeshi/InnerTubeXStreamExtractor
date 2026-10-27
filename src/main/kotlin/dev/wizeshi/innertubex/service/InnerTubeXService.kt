package dev.wizeshi.innertubex.service

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import dev.wizeshi.innertubex.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

class InnerTubeXService {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val innerTube = InnerTube(httpClient)
    private val configStore = RemotePlayerConfigStore(httpClient, PlayerConfigRepository.disabled())
    private val cipherService = YouTubeCipherService(httpClient, configStore)
    private val extractor = InnerTubeExtractor(
        configParser = YtConfigParserImpl(
            httpClient = httpClient,
            innerTube = innerTube,
            remotePlayerConfigStore = configStore,
            cipherService = cipherService
        ),
        cipherService = cipherService,
        innerTube = innerTube,
    )

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val sessionCookies = ConcurrentHashMap<String, String>()
    private var authenticatedUser: AuthStatusResponse = AuthStatusResponse(authenticated = false)

    fun updateSessionCookies(cookies: Map<String, String>): AuthStatusResponse {
        sessionCookies.clear()
        sessionCookies.putAll(cookies)

        val cookieStr = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        innerTube.cookie = cookieStr

        val hasAuthCookies = innerTube.hasSapCookieAuth() || cookies.containsKey("SAPISID") || cookies.containsKey("LOGIN_INFO")
        authenticatedUser = if (hasAuthCookies) {
            AuthStatusResponse(
                authenticated = true,
                userDisplayName = "YouTube Music User",
                userId = cookies["SAPISID"]?.hashCode()?.toString()
            )
        } else {
            AuthStatusResponse(authenticated = false)
        }
        return authenticatedUser
    }

    fun getAuthStatus(): AuthStatusResponse = authenticatedUser

    fun clearSession(): AuthStatusResponse {
        sessionCookies.clear()
        innerTube.cookie = null
        innerTube.visitorData = null
        authenticatedUser = AuthStatusResponse(authenticated = false)
        return authenticatedUser
    }

    suspend fun resolveStream(videoId: String, basePort: Int): ResolveResponse? = withContext(Dispatchers.IO) {
        try {
            val stream = extractor.extract(
                videoId = videoId,
                hints = ContentHints(wantVideo = false),
                audioQuality = AudioQuality.HIGH,
            )

            if (stream != null && stream.audioUrl.isNotBlank()) {
                val isOpus = stream.mimeType?.contains("webm") == true || stream.mimeType?.contains("opus") == true
                val bitrate = (stream.bitrate ?: 160000) / 1000
                return@withContext ResolveResponse(
                    videoId = videoId,
                    url = stream.audioUrl,
                    format = if (isOpus) "opus" else "m4a",
                    bitrate = bitrate,
                    isSabr = stream.sabrBootstrap != null,
                )
            }
        } catch (e: Exception) {
            System.err.println("InnerTubeExtractor failed for $videoId: ${e.message}")
            if (e is com.metrolist.innertubex.extraction.StreamResolveException) {
                System.err.println("Diagnostics reason: ${e.reason}")
                e.diagnostics?.attempts?.forEach { attempt ->
                    System.err.println("Attempt client: ${attempt.clientName}, profile: ${attempt.profileId}, outcome: ${attempt.outcome}")
                }
            }
            e.printStackTrace()
        }
        null
    }

    suspend fun getTrackDetails(videoId: String): TrackDetails = withContext(Dispatchers.IO) {
        try {
            val res = innerTube.player(YouTubeClient.WEB, videoId)
            val player = res.body<PlayerResponse>()
            val details = player.videoDetails
            val streamingData = player.streamingData

            val title = details?.title ?: "Unknown Title"
            val author = details?.author ?: "Unknown Artist"
            val authorId = details?.channelId
            val lengthSec = details?.lengthSeconds?.toLongOrNull() ?: 0L
            val thumbUrl = details?.thumbnail?.thumbnails?.lastOrNull()?.url
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            val formats = mutableListOf<FormatInfo>()
            streamingData?.adaptiveFormats?.forEach { format ->
                if (format.mimeType.startsWith("audio/")) {
                    formats.add(
                        FormatInfo(
                            itag = format.itag,
                            mimeType = format.mimeType,
                            bitrate = format.bitrate / 1000,
                            url = format.url,
                            contentLength = format.contentLength,
                            sampleRate = format.audioSampleRate,
                            isSabr = false,
                        )
                    )
                }
            }

            TrackDetails(
                videoId = videoId,
                title = title,
                author = author,
                authorId = authorId,
                durationSeconds = lengthSec,
                thumbnailUrl = thumbUrl,
                formats = formats,
                hasLyrics = true,
                isSabrAvailable = false
            )
        } catch (e: Exception) {
            System.err.println("Failed to fetch track details for $videoId: ${e.message}")
            TrackDetails(
                videoId = videoId,
                title = "Unknown",
                author = "Unknown Artist",
                durationSeconds = 0,
                thumbnailUrl = "",
                formats = emptyList(),
                hasLyrics = false,
                isSabrAvailable = false
            )
        }
    }

    private fun extractRunsText(element: JsonElement?): String? {
        if (element == null) return null
        if (element is JsonPrimitive) return element.contentOrNull
        if (element is JsonObject) {
            val runs = element["runs"]?.jsonArray
            if (runs != null) {
                return runs.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("")
                    .trim()
                    .ifEmpty { null }
            }
            val simpleText = element["simpleText"]?.jsonPrimitive?.contentOrNull
            if (simpleText != null) return simpleText
        }
        return null
    }

    private fun extractThumbnailUrl(element: JsonElement?): String? {
        if (element == null) return null
        val obj = if (element is JsonObject) element else return null
        val thumbs = obj["thumbnails"]?.jsonArray
            ?: obj["musicThumbnailRenderer"]?.jsonObject?.get("thumbnail")?.jsonObject?.get("thumbnails")?.jsonArray
            ?: obj["thumbnail"]?.jsonObject?.get("thumbnails")?.jsonArray
            ?: obj["croppedSquareThumbnailRenderer"]?.jsonObject?.get("thumbnail")?.jsonObject?.get("thumbnails")?.jsonArray
        return thumbs?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
    }

    private fun parseDurationSeconds(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(":")
        return when (parts.size) {
            1 -> parts[0].toLongOrNull()
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val s = parts[1].toLongOrNull() ?: return null
                m * 60 + s
            }
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toLongOrNull() ?: return null
                h * 3600 + m * 60 + s
            }
            else -> null
        }
    }

    private fun parseResponsiveListItem(itemObj: JsonObject): SearchResultItem? {
        val r = itemObj["musicResponsiveListItemRenderer"]?.jsonObject ?: itemObj
        val flexCols = r["flexColumns"]?.jsonArray ?: return null
        if (flexCols.isEmpty()) return null

        val col0Text = flexCols.getOrNull(0)?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject?.get("text")
        val title = extractRunsText(col0Text) ?: return null

        val col1Text = flexCols.getOrNull(1)?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject?.get("text")
        val col1Runs = col1Text?.jsonObject?.get("runs")?.jsonArray

        var itemType = "track"
        var artist: String? = null
        var album: String? = null

        if (col1Runs != null && col1Runs.isNotEmpty()) {
            val nonBulletRuns = col1Runs.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim() }
                .filter { it.isNotEmpty() && it != "•" && it != "·" && it != " - " }

            if (nonBulletRuns.isNotEmpty()) {
                val first = nonBulletRuns[0].lowercase()
                if (first == "artist") {
                    itemType = "artist"
                    artist = title
                } else if (first == "album" || first == "ep" || first == "single") {
                    itemType = "album"
                    artist = nonBulletRuns.getOrNull(1)
                } else if (first == "playlist") {
                    itemType = "playlist"
                    artist = nonBulletRuns.getOrNull(1)
                } else if (first == "song" || first == "video") {
                    itemType = "track"
                    artist = nonBulletRuns.getOrNull(1)
                    album = nonBulletRuns.getOrNull(2)
                } else {
                    artist = nonBulletRuns[0]
                    album = nonBulletRuns.getOrNull(1)
                }
            }
        }

        val fixedCols = r["fixedColumns"]?.jsonArray
        val durationText = extractRunsText(fixedCols?.getOrNull(0)?.jsonObject?.get("musicResponsiveListItemFixedColumnRenderer")?.jsonObject?.get("text"))
            ?: col1Runs?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }?.lastOrNull { it.contains(":") }
        val durationSec = parseDurationSeconds(durationText)

        val thumbUrl = extractThumbnailUrl(r["thumbnail"])

        val nav = r["navigationEndpoint"]?.jsonObject
        val watchNav = nav?.get("watchEndpoint")?.jsonObject
        val playBtnNav = r["overlay"]?.jsonObject?.get("musicItemThumbnailOverlayRenderer")?.jsonObject
            ?.get("content")?.jsonObject?.get("musicPlayButtonRenderer")?.jsonObject
            ?.get("playNavigationEndpoint")?.jsonObject?.get("watchEndpoint")?.jsonObject

        val videoId = r["playlistItemData"]?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: playBtnNav?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: watchNav?.get("videoId")?.jsonPrimitive?.contentOrNull

        val browseId = nav?.get("browseEndpoint")?.jsonObject?.get("browseId")?.jsonPrimitive?.contentOrNull

        val id = if (itemType == "track") (videoId ?: browseId) else (browseId ?: videoId)
        if (id.isNullOrBlank()) return null

        return SearchResultItem(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationSeconds = durationSec,
            thumbnailUrl = thumbUrl,
            type = itemType
        )
    }

    private fun parseTwoRowItem(itemObj: JsonObject): SearchResultItem? {
        val r = itemObj["musicTwoRowItemRenderer"]?.jsonObject ?: itemObj
        val title = extractRunsText(r["title"]) ?: return null
        val subtitle = extractRunsText(r["subtitle"])
        val thumbUrl = extractThumbnailUrl(r["thumbnailRenderer"])

        val nav = r["navigationEndpoint"]?.jsonObject
        val browseId = nav?.get("browseEndpoint")?.jsonObject?.get("browseId")?.jsonPrimitive?.contentOrNull
        val videoId = nav?.get("watchEndpoint")?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull

        var itemType = "album"
        val subLower = subtitle?.lowercase() ?: ""
        if (subLower.contains("artist")) {
            itemType = "artist"
        } else if (subLower.contains("playlist")) {
            itemType = "playlist"
        } else if (subLower.contains("single") || subLower.contains("ep") || subLower.contains("album")) {
            itemType = "album"
        } else if (subLower.contains("song")) {
            itemType = "track"
        } else if (browseId?.startsWith("UC") == true) {
            itemType = "artist"
        } else if (browseId?.startsWith("MPRE") == true) {
            itemType = "album"
        } else if (browseId?.startsWith("VL") == true) {
            itemType = "playlist"
        }

        val id = browseId ?: videoId ?: return null

        return SearchResultItem(
            id = id,
            title = title,
            artist = subtitle,
            album = null,
            durationSeconds = null,
            thumbnailUrl = thumbUrl,
            type = itemType
        )
    }

    private fun collectSearchResultItems(element: JsonElement, items: MutableList<SearchResultItem>) {
        when (element) {
            is JsonObject -> {
                if (element.containsKey("musicResponsiveListItemRenderer")) {
                    parseResponsiveListItem(element)?.let { items.add(it) }
                    return
                }
                if (element.containsKey("musicTwoRowItemRenderer")) {
                    parseTwoRowItem(element)?.let { items.add(it) }
                    return
                }
                for (value in element.values) {
                    collectSearchResultItems(value, items)
                }
            }
            is JsonArray -> {
                for (item in element) {
                    collectSearchResultItems(item, items)
                }
            }
            else -> {}
        }
    }

    private fun findHeaderRenderer(element: JsonElement): JsonObject? {
        if (element is JsonObject) {
            if (element.containsKey("musicResponsiveHeaderRenderer")) return element["musicResponsiveHeaderRenderer"]?.jsonObject
            if (element.containsKey("musicDetailHeaderRenderer")) return element["musicDetailHeaderRenderer"]?.jsonObject
            if (element.containsKey("musicImmersiveHeaderRenderer")) return element["musicImmersiveHeaderRenderer"]?.jsonObject
            if (element.containsKey("musicVisualHeaderRenderer")) return element["musicVisualHeaderRenderer"]?.jsonObject
            if (element.containsKey("musicHeaderRenderer")) return element["musicHeaderRenderer"]?.jsonObject
            if (element.containsKey("musicEditablePlaylistDetailHeaderRenderer")) return element["musicEditablePlaylistDetailHeaderRenderer"]?.jsonObject
            for (value in element.values) {
                val found = findHeaderRenderer(value)
                if (found != null) return found
            }
        } else if (element is JsonArray) {
            for (item in element) {
                val found = findHeaderRenderer(item)
                if (found != null) return found
            }
        }
        return null
    }

    suspend fun search(query: String, type: String?, limit: Int): dev.wizeshi.innertubex.models.SearchResponse = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = innerTube.search(
                client = YouTubeClient.WEB_REMIX,
                query = query,
                setLogin = innerTube.hasSapCookieAuth()
            )
            val jsonBody = jsonParser.parseToJsonElement(response.bodyAsText())
            val allItems = mutableListOf<SearchResultItem>()
            collectSearchResultItems(jsonBody, allItems)

            var filtered = if (!type.isNullOrBlank()) {
                val target = type.lowercase()
                allItems.filter { it.type.lowercase() == target }
            } else {
                allItems
            }

            if (filtered.isEmpty() && allItems.isNotEmpty()) {
                filtered = allItems
            }

            dev.wizeshi.innertubex.models.SearchResponse(items = filtered.take(limit))
        } catch (e: Exception) {
            System.err.println("InnerTube search failed: ${e.message}")
            e.printStackTrace()
            dev.wizeshi.innertubex.models.SearchResponse(items = emptyList())
        }
    }

    suspend fun getAlbumDetails(browseId: String): AlbumDetails = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = innerTube.browse(
                client = YouTubeClient.WEB_REMIX,
                browseId = browseId,
                setLogin = innerTube.hasSapCookieAuth()
            )
            val jsonBody = jsonParser.parseToJsonElement(response.bodyAsText())
            val header = findHeaderRenderer(jsonBody)

            val title = extractRunsText(header?.get("title")) ?: "Unknown Album"
            val artist = extractRunsText(header?.get("straplineTextOne") ?: header?.get("subtitle")) ?: "Unknown Artist"
            val artistId = header?.get("straplineTextOne")?.jsonObject?.get("runs")?.jsonArray?.getOrNull(0)
                ?.jsonObject?.get("navigationEndpoint")?.jsonObject?.get("browseEndpoint")?.jsonObject?.get("browseId")?.jsonPrimitive?.contentOrNull
            val subtitleText = extractRunsText(header?.get("subtitle"))
            val year = subtitleText?.split("•", "·", " ")?.firstOrNull { it.trim().length == 4 && it.trim().all { c -> c.isDigit() } }?.trim()
            val thumbUrl = extractThumbnailUrl(header?.get("thumbnail")) ?: "https://i.ytimg.com/vi/$browseId/hqdefault.jpg"

            val rawItems = mutableListOf<SearchResultItem>()
            collectSearchResultItems(jsonBody, rawItems)
            val trackItems = rawItems.filter { it.type == "track" }

            val tracks = trackItems.mapIndexed { idx, it ->
                AlbumTrack(
                    videoId = it.id,
                    title = it.title,
                    artist = it.artist ?: artist,
                    durationSeconds = it.durationSeconds ?: 0L,
                    index = idx + 1
                )
            }

            AlbumDetails(
                browseId = browseId,
                title = title,
                artist = artist,
                artistId = artistId,
                year = year,
                thumbnailUrl = thumbUrl,
                trackCount = tracks.size,
                tracks = tracks
            )
        } catch (e: Exception) {
            System.err.println("Failed to get album details for $browseId: ${e.message}")
            AlbumDetails(
                browseId = browseId,
                title = "Album $browseId",
                artist = "Unknown Artist",
                thumbnailUrl = "https://i.ytimg.com/vi/$browseId/hqdefault.jpg",
                trackCount = 0,
                tracks = emptyList()
            )
        }
    }

    suspend fun getArtistDetails(browseId: String): ArtistDetails = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = innerTube.browse(
                client = YouTubeClient.WEB_REMIX,
                browseId = browseId,
                setLogin = innerTube.hasSapCookieAuth()
            )
            val jsonBody = jsonParser.parseToJsonElement(response.bodyAsText())
            val header = findHeaderRenderer(jsonBody)

            val name = extractRunsText(header?.get("title")) ?: "Artist"
            val avatarUrl = extractThumbnailUrl(header?.get("thumbnail")) ?: "https://i.ytimg.com/vi/$browseId/hqdefault.jpg"

            var bio: String? = null
            if (jsonBody is JsonObject) {
                fun findDescription(e: JsonElement): String? {
                    if (e is JsonObject) {
                        if (e.containsKey("musicDescriptionShelfRenderer")) {
                            return extractRunsText(e["musicDescriptionShelfRenderer"]?.jsonObject?.get("description"))
                        }
                        for (v in e.values) {
                            val desc = findDescription(v)
                            if (desc != null) return desc
                        }
                    } else if (e is JsonArray) {
                        for (v in e) {
                            val desc = findDescription(v)
                            if (desc != null) return desc
                        }
                    }
                    return null
                }
                bio = findDescription(jsonBody)
            }

            val allItems = mutableListOf<SearchResultItem>()
            collectSearchResultItems(jsonBody, allItems)

            val topTracks = allItems.filter { it.type == "track" }
            val albums = allItems.filter { it.type == "album" }

            ArtistDetails(
                browseId = browseId,
                name = name,
                bio = bio,
                avatarUrl = avatarUrl,
                topTracks = topTracks,
                albums = albums,
                singles = emptyList()
            )
        } catch (e: Exception) {
            System.err.println("Failed to get artist details for $browseId: ${e.message}")
            ArtistDetails(
                browseId = browseId,
                name = "Artist $browseId",
                bio = "Official YouTube Music artist channel.",
                avatarUrl = "https://i.ytimg.com/vi/$browseId/hqdefault.jpg"
            )
        }
    }

    suspend fun getPlaylistDetails(browseId: String): PlaylistDetails = withContext(Dispatchers.IO) {
        try {
            val targetBrowseId = if (browseId.startsWith("PL")) "VL$browseId" else browseId
            var response = innerTube.browse(
                client = YouTubeClient.WEB_REMIX,
                browseId = targetBrowseId,
                setLogin = innerTube.hasSapCookieAuth()
            )
            var jsonBody = jsonParser.parseToJsonElement(response.bodyAsText())
            var header = findHeaderRenderer(jsonBody)

            if (header == null) {
                // Fallback to WEB client
                response = innerTube.browse(
                    client = YouTubeClient.WEB,
                    browseId = targetBrowseId,
                    setLogin = innerTube.hasSapCookieAuth()
                )
                jsonBody = jsonParser.parseToJsonElement(response.bodyAsText())
                header = findHeaderRenderer(jsonBody)
            }

            val title = extractRunsText(header?.get("title")) ?: "Playlist"
            val author = extractRunsText(header?.get("subtitle") ?: header?.get("ownerText")) ?: "YouTube"
            val description = extractRunsText(header?.get("description"))
            val thumbUrl = extractThumbnailUrl(header?.get("thumbnail")) ?: "https://i.ytimg.com/vi/$browseId/hqdefault.jpg"

            val allItems = mutableListOf<SearchResultItem>()
            collectSearchResultItems(jsonBody, allItems)
            val tracks = allItems.filter { it.type == "track" }

            PlaylistDetails(
                browseId = browseId,
                title = title,
                author = author,
                description = description,
                trackCount = tracks.size,
                thumbnailUrl = thumbUrl,
                tracks = tracks
            )
        } catch (e: Exception) {
            System.err.println("Failed to get playlist details for $browseId: ${e.message}")
            PlaylistDetails(
                browseId = browseId,
                title = "Playlist $browseId",
                author = "YouTube",
                trackCount = 0,
                thumbnailUrl = "https://i.ytimg.com/vi/$browseId/hqdefault.jpg"
            )
        }
    }

    suspend fun getLikedTracks(): dev.wizeshi.innertubex.models.SearchResponse = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = innerTube.browse(
                client = YouTubeClient.WEB_REMIX,
                browseId = "FEmusic_liked_videos",
                setLogin = true
            )
            val jsonBody = jsonParser.parseToJsonElement(response.bodyAsText())
            val allItems = mutableListOf<SearchResultItem>()
            collectSearchResultItems(jsonBody, allItems)
            dev.wizeshi.innertubex.models.SearchResponse(items = allItems.filter { it.type == "track" })
        } catch (e: Exception) {
            System.err.println("Failed to fetch liked tracks: ${e.message}")
            dev.wizeshi.innertubex.models.SearchResponse(items = emptyList())
        }
    }
}
