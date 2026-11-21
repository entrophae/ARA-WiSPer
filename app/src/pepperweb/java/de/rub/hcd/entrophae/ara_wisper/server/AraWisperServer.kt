package de.rub.hcd.entrophae.ara_wisper.server

import android.util.Log
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import de.rub.hcd.entrophae.ara_wisper.server.routes.dashboardRouting
import de.rub.hcd.entrophae.ara_wisper.server.routes.proxyRouting
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager.appAssets
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.network.getOrCreateDynamicKeyStore
import de.rub.hcd.entrophae.ara_wisper.server.routes.ragRouting
import de.rub.hcd.entrophae.ara_wisper.server.routes.constantsRouting
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngineEnvironment
import io.ktor.server.engine.ApplicationEngineEnvironmentBuilder
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean

class AraWisperServer() {
    companion object {
        private val lifecycleMutex = Mutex()
    }

    @Volatile
    private var server: NettyApplicationEngine? = null
    private val stopped = AtomicBoolean(false)
    val serverJob = Job()
    val serverScope = CoroutineScope(Dispatchers.IO + serverJob)

    private fun start(viewModel: ChatViewModel) {
        if (stopped.get()) return
        Log.d(ProxySettingsRepository.TAG, "Configuring Ktor Server (http=${ProxySettingsRepository.port}, https=${ProxySettingsRepository.sslPort})...")
        val env = createEnvironment(viewModel)
        if (stopped.get()) return

        var engine: NettyApplicationEngine? = null
        try {
            engine = embeddedServer(Netty, env)
            if (stopped.get()) return
            engine.start(wait = false)

            if (stopped.get()) {
                engine.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
                return
            }

            server = engine
            Log.d(ProxySettingsRepository.TAG, "Ktor Server successfully listening on port ${ProxySettingsRepository.port}")
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "failed to start server on ports ${ProxySettingsRepository.port}/${ProxySettingsRepository.sslPort}: ${e.message}")
            runCatching { engine?.stop(0, 0) }
            throw e
        }
    }

    fun startServer(viewModel: ChatViewModel) {
        serverScope.launch {
            lifecycleMutex.withLock {
                try {
                    start(viewModel)
                    if (!stopped.get()) {
                        Log.d(ProxySettingsRepository.TAG, "web server started on port ${ProxySettingsRepository.port}")
                    }
                } catch (e: Exception) {
                    Log.e(ProxySettingsRepository.TAG, "failed to start server: ${e.message}")
                }
            }
        }
    }

    fun stopServer() {
        stopped.set(true)
        CoroutineScope(Dispatchers.IO).launch {
            lifecycleMutex.withLock {
                Log.d(ProxySettingsRepository.TAG, "Shutting down Ktor Server...")
                server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
                server = null
                serverJob.cancel()
                Log.d(ProxySettingsRepository.TAG, "web server stopped")
            }
        }
    }

    private fun createEnvironment(viewModel: ChatViewModel): ApplicationEngineEnvironment =
        applicationEngineEnvironment {
            configureConnectors()
            module {
                install(WebSockets.Plugin)
                routing {
                    constantsRouting(viewModel)
                    dashboardRouting(viewModel)
                    proxyRouting()
                    ragRouting()

                    get("/") {
                        try {
                            val html =
                                appAssets.open("web/index.html").bufferedReader()
                                    .use { it.readText() }
                            call.respondText(html, ContentType.Text.Html)
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                "index.html not found in assets/web/"
                            )
                        }
                    }

                    // Dynamic routing for all files
                    get("/{fileName...}") {
                        val path =
                            call.parameters.getAll("fileName")?.joinToString("/") ?: return@get

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
        }

    private fun ApplicationEngineEnvironmentBuilder.configureConnectors() {
        connector {
            this.port = ProxySettingsRepository.port
            this.host = "0.0.0.0"
        }
        sslConnector(
            keyStore = getOrCreateDynamicKeyStore(),
            keyAlias = ProxySettingsRepository.keyAlias,
            keyStorePassword = { ProxySettingsRepository.ksPassword.toCharArray() },
            privateKeyPassword = { ProxySettingsRepository.ksPassword.toCharArray() }
        ) {
            this.port = ProxySettingsRepository.sslPort
            this.host = "0.0.0.0"
        }
    }

}