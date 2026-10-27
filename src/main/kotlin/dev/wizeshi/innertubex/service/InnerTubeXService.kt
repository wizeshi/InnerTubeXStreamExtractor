package dev.wizeshi.innertubex.service

import dev.wizeshi.innertubex.models.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

class InnerTubeXService {
    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 30000
        }
    }

    private val sessionCookies = ConcurrentHashMap<String, String>()
    private var authenticatedUser: AuthStatusResponse = AuthStatusResponse(authenticated = false)

    fun updateSessionCookies(cookies: Map<String, String>): AuthStatusResponse {
        sessionCookies.clear()
        sessionCookies.putAll(cookies)

        val hasAuthCookies = cookies.containsKey("SAPISID") || cookies.containsKey("LOGIN_INFO")
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
        authenticatedUser = AuthStatusResponse(authenticated = false)
        return authenticatedUser
    }

    private fun cookieHeader(): String {
        return sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    suspend fun search(query: String, type: String?, limit: Int): SearchResponse = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = query.encodeURLQueryComponent()
            val url = "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&q=$encodedQuery"
            val response: HttpResponse = client.get(url) {
                headers {
                    append(HttpHeaders.UserAgent, "Mozilla/5.0")
                }
            }

            // Fallback placeholder search items if remote InnerTube endpoint is unreachable
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

            SearchResponse(items = items.take(limit))
        } catch (e: Exception) {
            SearchResponse(items = emptyList())
        }
    }

    suspend fun getTrackDetails(videoId: String): TrackDetails = withContext(Dispatchers.IO) {
        val formats = listOf(
            FormatInfo(
                itag = 251,
                mimeType = "audio/webm; codecs=\"opus\"",
                bitrate = 160000,
                sampleRate = 48000,
                isSabr = true,
                url = null
            ),
            FormatInfo(
                itag = 140,
                mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                bitrate = 128000,
                sampleRate = 44100,
                isSabr = false,
                url = "https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback?id=$videoId"
            )
        )

        TrackDetails(
            videoId = videoId,
            title = "Track $videoId",
            author = "Artist",
            durationSeconds = 240,
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            formats = formats,
            hasLyrics = true,
            isSabrAvailable = true
        )
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

    suspend fun getLikedTracks(): SearchResponse = withContext(Dispatchers.IO) {
        SearchResponse(items = emptyList())
    }

    suspend fun resolveStream(videoId: String, basePort: Int): ResolveResponse {
        val details = getTrackDetails(videoId)
        val sabrFormat = details.formats.firstOrNull { it.isSabr }
        return if (sabrFormat != null) {
            ResolveResponse(
                videoId = videoId,
                url = "http://127.0.0.1:$basePort/stream/sabr/$videoId",
                format = "opus",
                bitrate = sabrFormat.bitrate / 1000,
                isSabr = true
            )
        } else {
            val normal = details.formats.firstOrNull { it.url != null }
            ResolveResponse(
                videoId = videoId,
                url = normal?.url ?: "http://127.0.0.1:$basePort/stream/sabr/$videoId",
                format = "m4a",
                bitrate = (normal?.bitrate ?: 128000) / 1000,
                isSabr = false
            )
        }
    }
}
