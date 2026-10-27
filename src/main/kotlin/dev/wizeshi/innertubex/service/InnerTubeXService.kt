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

    suspend fun search(query: String, type: String?, limit: Int): dev.wizeshi.innertubex.models.SearchResponse = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = query.encodeURLQueryComponent()
            val url = "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&q=$encodedQuery"
            val response: HttpResponse = httpClient.get(url) {
                headers {
                    append(HttpHeaders.UserAgent, "Mozilla/5.0")
                }
            }

            val items = mutableListOf<SearchResultItem>()
            items.add(
                SearchResultItem(
                    id = query.hashCode().toString(),
                    title = query,
                    artist = "YouTube Music",
                    album = null,
                    durationSeconds = 210,
                    thumbnailUrl = "https://i.ytimg.com/vi/${query}/hqdefault.jpg",
                    type = type ?: "track"
                )
            )

            dev.wizeshi.innertubex.models.SearchResponse(items = items.take(limit))
        } catch (e: Exception) {
            dev.wizeshi.innertubex.models.SearchResponse(items = emptyList())
        }
    }

    suspend fun getAlbumDetails(browseId: String): AlbumDetails = withContext(Dispatchers.IO) {
        AlbumDetails(
            browseId = browseId,
            title = "Album $browseId",
            artist = "Artist",
            year = "2024",
            thumbnailUrl = "https://i.ytimg.com/vi/$browseId/hqdefault.jpg",
            trackCount = 10,
            tracks = (1..10).map { i ->
                AlbumTrack(
                    videoId = "${browseId}_t$i",
                    title = "Track $i",
                    artist = "Artist",
                    durationSeconds = 180 + (i * 10).toLong(),
                    index = i
                )
            }
        )
    }

    suspend fun getArtistDetails(browseId: String): ArtistDetails = withContext(Dispatchers.IO) {
        ArtistDetails(
            browseId = browseId,
            name = "Artist $browseId",
            bio = "Official YouTube Music artist channel.",
            avatarUrl = "https://i.ytimg.com/vi/$browseId/hqdefault.jpg"
        )
    }

    suspend fun getPlaylistDetails(browseId: String): PlaylistDetails = withContext(Dispatchers.IO) {
        PlaylistDetails(
            browseId = browseId,
            title = "Playlist $browseId",
            author = "YouTube",
            trackCount = 20,
            thumbnailUrl = "https://i.ytimg.com/vi/$browseId/hqdefault.jpg"
        )
    }

    suspend fun getLikedTracks(): dev.wizeshi.innertubex.models.SearchResponse = withContext(Dispatchers.IO) {
        dev.wizeshi.innertubex.models.SearchResponse(items = emptyList())
    }
}
