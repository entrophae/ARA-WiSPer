package de.rub.hcd.entrophae.ara_wisper.server.routes

import android.util.Log
import ch.fhnw.pepper_realtime.R
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager.appString
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.manager.ProxyStateBridge
import de.rub.hcd.entrophae.ara_wisper.server.manager.DashboardManager
import de.rub.hcd.entrophae.ara_wisper.server.manager.ProxyPipelineManager
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import de.rub.hcd.entrophae.ara_wisper.server.manager.sendToApp
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.json.JSONObject

/**
 * Ktor WebSocket route implementing the local OpenAI Realtime-compatible proxy endpoint.
 *
 * Attaches and detaches the active WebSocket session on [ProxyStateBridge], sends the
 * initial session handshake, and dispatches incoming Realtime protocol frames
 * (`session.update`, `input_audio_buffer.append`, `conversation.item.create`,
 * `response.create`, `response.cancel`) to [ProxyPipelineManager].
 */
fun Route.proxyRouting() {
    webSocket(ProxySettingsRepository.Routes.PROXY) {
        Log.i(appString(R.string.app_name), appString(R.string.proxy_connected))

        ProxyStateBridge.attachSession( scope = this ) { packet ->
            sendToApp(packet)
        }
        val manager = ProxyPipelineManager(serverScope = this)

        // Send initial connection handshake
        val handshake = WebSocketPayloads.sessionCreated()
        send(Frame.Text(handshake))
        DashboardManager.sendLog(handshake, isFromRobot = false)

        DashboardManager.recreateSession {
            sendToApp(it)
        }
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val rawJson = frame.readText()
                    val json = JSONObject(rawJson)
                    val type = json.optString(ProxySettingsRepository.Proxy.Keys.TYPE)

                    when (type) {
                        ProxySettingsRepository.Proxy.Events.SESSION_UPDATE -> manager.handleSessionUpdate(
                            json,
                            rawJson
                        )

                        ProxySettingsRepository.Proxy.Events.RESP_CANCEL -> manager.updateIsResponseCancelled(
                            true
                        )

                        ProxySettingsRepository.Proxy.Events.CONV_ITEM_TRUNC -> manager.handleItemTruncated(
                            json
                        )

                        ProxySettingsRepository.Proxy.Events.INPUT_BUFFER_APPEND -> manager.handleAudioAppend(
                            json
                        )

                        ProxySettingsRepository.Proxy.Events.CONV_ITEM_CREATE -> manager.handleItemCreate(
                            json,
                            rawJson
                        )

                        ProxySettingsRepository.Proxy.Events.RESP_CREATE -> manager.handleRespCreate(
                            rawJson
                        )
                    }
                }
            }
        }
        finally {
            manager.updateIsResponseCancelled(true)
            manager.audioProcessor.close()
            ProxyStateBridge.detachSession()
        }
    }
}
