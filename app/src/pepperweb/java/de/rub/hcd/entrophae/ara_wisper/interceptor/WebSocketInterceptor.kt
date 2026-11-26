package de.rub.hcd.entrophae.ara_wisper.interceptor

import android.util.Log
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import ch.fhnw.pepper_realtime.network.WebSocketConnectionCallback
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository

/**
 * Outbound WebSocket connection interceptor hooking the host app into the local proxy.
 *
 * Reroutes Realtime API WebSocket connections from `pepper_realtime` to the embedded
 * [AraWisperServer] proxy endpoint when custom/local LLM mode is active.
 */
object WebSocketInterceptor {

    fun interceptConnection(
        tag: String,
        sessionManager: RealtimeSessionManager,
        callback: WebSocketConnectionCallback?
    ): Boolean {
        if (!ProxySettingsRepository.isCustomProxyRouting) {
            return false
        }

        if (!AraWisperManager.isSessionAllowed.value) {
            Log.i(tag, "Connection blocked by AraWisperManager. Waiting for manual start.")
            return true
        }

        val localProxyUrl = "ws://127.0.0.1:" + ProxySettingsRepository.port + ProxySettingsRepository.Routes.PROXY
        Log.i(tag, "Routing connection directly to local proxy: $localProxyUrl")

        try {
            Log.i(tag, "Entering the Proxy")
            sessionManager.connect(localProxyUrl, null)
        } catch (e: Exception) {
            Log.e(tag, "Error connecting to local proxy", e)
            callback?.onError(e)
        }

        return true

    }
}