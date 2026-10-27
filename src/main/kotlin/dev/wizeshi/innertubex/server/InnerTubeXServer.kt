package dev.wizeshi.innertubex.server

import dev.wizeshi.innertubex.models.*
import dev.wizeshi.innertubex.service.InnerTubeXService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

class InnerTubeXServer(
    private val service: InnerTubeXService = InnerTubeXService()
) {
    private var actualPort: Int = 0

    fun start(port: Int = 0): Int {
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }

            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.Range)
            }

            routing {
                get("/health") {
                    call.respond(mapOf("status" to "ok"))
                }

                post("/search") {
                    val req = call.receive<SearchRequest>()
                    val res = service.search(req.query, req.type, req.limit)
                    call.respond(res)
                }

                get("/track/{videoId}") {
                    val videoId = call.parameters["videoId"]
                    if (videoId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing videoId")
                        return@get
                    }
                    val details = service.getTrackDetails(videoId)
                    call.respond(details)
                }

                get("/album/{browseId}") {
                    val browseId = call.parameters["browseId"]
                    if (browseId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing browseId")
                        return@get
                    }
                    val details = service.getAlbumDetails(browseId)
                    call.respond(details)
                }

                get("/artist/{browseId}") {
                    val browseId = call.parameters["browseId"]
                    if (browseId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing browseId")
                        return@get
                    }
                    val details = service.getArtistDetails(browseId)
                    call.respond(details)
                }

                get("/playlist/{browseId}") {
                    val browseId = call.parameters["browseId"]
                    if (browseId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing browseId")
                        return@get
                    }
                    val details = service.getPlaylistDetails(browseId)
                    call.respond(details)
                }

                post("/auth/session") {
                    val req = call.receive<AuthSessionRequest>()
                    val status = service.updateSessionCookies(req.cookies)
                    call.respond(status)
                }

                get("/auth/status") {
                    call.respond(service.getAuthStatus())
                }

                post("/auth/logout") {
                    call.respond(service.clearSession())
                }

                get("/library/liked") {
                    call.respond(service.getLikedTracks())
                }

                get("/resolve/{videoId}") {
                    val videoId = call.parameters["videoId"]
                    if (videoId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing videoId")
                        return@get
                    }
                    val result = service.resolveStream(videoId, actualPort)
                    if (result == null || result.url.isBlank()) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Stream unavailable for $videoId"))
                    } else {
                        call.respond(result)
                    }
                }

                get("/stream/sabr/{videoId}") {
                    val videoId = call.parameters["videoId"]
                    if (videoId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing videoId")
                        return@get
                    }
                    call.respond(HttpStatusCode.NotFound, "SABR stream proxy not available")
                }
            }
        }.start(wait = false)

        actualPort = kotlinx.coroutines.runBlocking {
            server.engine.resolvedConnectors().first().port
        }
        return actualPort
    }
}
