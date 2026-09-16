package de.rub.hcd.entrophae.ara_wisper.server

import android.util.Log
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import de.rub.hcd.entrophae.ara_wisper.server.routes.dashboardRouting
import de.rub.hcd.entrophae.ara_wisper.server.routes.proxyRouting
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager.appAssets
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.routes.ragRouting
import de.rub.hcd.entrophae.ara_wisper.server.routes.constantsRouting
import de.rub.hcd.entrophae.ara_wisper.server.routes.experimentRouting
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.FileNotFoundException

class AraWisperServer(private val port: Int = 8080) {
    private var server: NettyApplicationEngine? = null
    val serverJob = Job()
    val serverScope = CoroutineScope(Dispatchers.IO + serverJob)

    private fun start(viewModel: ChatViewModel, toolContext: ToolContext?) {
        Log.d(ProxySettingsRepository.TAG, "Configuring Ktor Server...")

        server = embeddedServer(Netty, port = port) {
            install(WebSockets.Plugin)

            routing {
                get("/") {
                    try {
                        val html =
                            appAssets.open("web/index.html").bufferedReader().use { it.readText() }
                        call.respondText(html, ContentType.Text.Html)
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            "index.html not found in assets/web/"
                        )
                    }
                }

                constantsRouting(viewModel)
                dashboardRouting(viewModel)
                proxyRouting(toolContext)

                // Dynamic routing for all files
                get("/{fileName...}") {
                    val path = call.parameters.getAll("fileName")?.joinToString("/") ?: return@get

                    try {
                        val fileContent =
                            appAssets.open("web/$path").bufferedReader().use { it.readText() }
                        val contentType = when {
                            path.endsWith(".html") -> ContentType.Text.Html
                            path.endsWith(".css") -> ContentType.Text.CSS
                            path.endsWith(".js") -> ContentType.Text.JavaScript
                            path.endsWith(".json") -> ContentType.Application.Json
                            else -> ContentType.Text.Plain
                        }
                        call.respondText(fileContent, contentType)
                    } catch (e: FileNotFoundException) {
                        call.respond(HttpStatusCode.NotFound, "File not found")
                    }
                }
            }
        }
        server?.start(wait = false)
        Log.d(ProxySettingsRepository.TAG, "Ktor Server successfully listening on port $port")
    }

    private fun stop() {
        Log.d(ProxySettingsRepository.TAG, "Shutting down Ktor Server...")
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
        Log.d(ProxySettingsRepository.TAG, "web server stopped")
    }

    fun startServer(viewModel: ChatViewModel, toolContext: ToolContext?) {
        serverScope.launch {
            try {
                start(viewModel, toolContext)
                Log.d(ProxySettingsRepository.TAG, "web server started on port $port")
            } catch (e: Exception) {
                Log.e(ProxySettingsRepository.TAG, "failed to start server: ${e.message}")
            }
        }
    }

    fun stopServer() {
        stop()
        serverJob.cancel()
    }

}