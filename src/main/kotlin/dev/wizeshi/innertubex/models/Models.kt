package dev.wizeshi.innertubex.models

import kotlinx.serialization.Serializable

@Serializable
data class ReadyNotification(
    val status: String,
    val port: Int
)

@Serializable
data class SearchRequest(
    val query: String,
    val type: String? = null,
    val limit: Int = 20
)

@Serializable
data class SearchResultItem(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val type: String // "track", "album", "artist", "playlist"
)

@Serializable
data class SearchResponse(
    val items: List<SearchResultItem>
)

@Serializable
data class FormatInfo(
    val itag: Int,
    val mimeType: String,
    val bitrate: Int,
    val sampleRate: Int? = null,
    val contentLength: Long? = null,
    val isSabr: Boolean = false,
    val url: String? = null
)

@Serializable
data class TrackDetails(
    val videoId: String,
    val title: String,
    val author: String,
    val authorId: String? = null,
    val durationSeconds: Long,
    val description: String? = null,
    val viewCount: Long? = null,
    val thumbnailUrl: String? = null,
    val formats: List<FormatInfo> = emptyList(),
    val hasLyrics: Boolean = false,
    val isSabrAvailable: Boolean = false
)

@Serializable
data class AlbumTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val index: Int
)

@Serializable
data class AlbumDetails(
    val browseId: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val year: String? = null,
    val thumbnailUrl: String? = null,
    val trackCount: Int,
    val tracks: List<AlbumTrack> = emptyList()
)

@Serializable
data class ArtistDetails(
    val browseId: String,
    val name: String,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val topTracks: List<SearchResultItem> = emptyList(),
    val albums: List<SearchResultItem> = emptyList(),
    val singles: List<SearchResultItem> = emptyList()
)

@Serializable
data class PlaylistDetails(
    val browseId: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val trackCount: Int,
    val thumbnailUrl: String? = null,
    val tracks: List<SearchResultItem> = emptyList()
)

@Serializable
data class AuthSessionRequest(
    val cookies: Map<String, String>
)

@Serializable
data class AuthStatusResponse(
    val authenticated: Boolean,
    val userDisplayName: String? = null,
    val userId: String? = null,
    val email: String? = null
)

@Serializable
data class ResolveResponse(
    val videoId: String,
    val url: String,
    val format: String,
    val bitrate: Int,
    val isSabr: Boolean
)
