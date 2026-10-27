package dev.wizeshi.innertubex

import dev.wizeshi.innertubex.models.ReadyNotification
import dev.wizeshi.innertubex.server.InnerTubeXServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    val port = if (args.isNotEmpty()) args[0].toIntOrNull() ?: 0 else 0
    val server = InnerTubeXServer()
    val boundPort = server.start(port)

    // Handshake output for Dart daemon supervisor:
    val notification = ReadyNotification(status = "ready", port = boundPort)
    println(Json.encodeToString(notification))

    // Keep daemon alive
    Thread.currentThread().join()
}
