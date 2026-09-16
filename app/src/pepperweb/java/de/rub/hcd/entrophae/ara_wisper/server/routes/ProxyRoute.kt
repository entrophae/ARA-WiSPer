package de.rub.hcd.entrophae.ara_wisper.server.routes

import android.util.Log
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.tools.ToolContext
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager.appString
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.manager.DashboardManager
import de.rub.hcd.entrophae.ara_wisper.server.manager.ProxyPipelineManager
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import de.rub.hcd.entrophae.ara_wisper.server.manager.sendToApp
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

fun Route.proxyRouting(
    activeToolContext: ToolContext?
) {
    webSocket(ProxySettingsRepository.Routes.PROXY) {
        Log.i(appString(R.string.app_name), appString(R.string.proxy_connected))

        val manager = ProxyPipelineManager(
            serverScope = this,
            activeToolContext
        ) { sendToApp(it) }

        // Send initial connection handshake
        val handshake = WebSocketPayloads.sessionCreated()
        send(Frame.Text(handshake))
        DashboardManager.sendLog(handshake, isFromRobot = false)

        manager.recreateSession()

        for (frame in incoming) {
            if (frame is Frame.Text) {
                val rawJson = frame.readText()
                val json = JSONObject(rawJson)
                val type = json.optString(ProxySettingsRepository.Proxy.Keys.TYPE)

                when (type) {
                    ProxySettingsRepository.Proxy.Events.SESSION_UPDATE -> manager.handleSessionUpdate(json, rawJson)
                    ProxySettingsRepository.Proxy.Events.RESP_CANCEL -> manager.updateIsResponseCancelled(true)
                    ProxySettingsRepository.Proxy.Events.CONV_ITEM_TRUNC -> manager.handleItemTruncated(json)
                    ProxySettingsRepository.Proxy.Events.INPUT_BUFFER_APPEND -> manager.handleAudioAppend(json)
                    ProxySettingsRepository.Proxy.Events.CONV_ITEM_CREATE -> manager.handleItemCreate(json, rawJson)
                    ProxySettingsRepository.Proxy.Events.RESP_CREATE -> manager.handleRespCreate(rawJson)
                }
            }
        }
        manager.audioProcessor.close()
    }
}
