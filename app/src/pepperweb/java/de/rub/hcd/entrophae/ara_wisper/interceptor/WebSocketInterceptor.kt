package de.rub.hcd.entrophae.ara_wisper.interceptor

import android.util.Log
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import ch.fhnw.pepper_realtime.network.WebSocketConnectionCallback
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository

object WebSocketInterceptor {

    fun interceptConnection(
        tag: String,
        sessionManager: RealtimeSessionManager,
        callback: WebSocketConnectionCallback?
    ): Boolean {
        if (!_root_ide_package_.de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository.isCustomProxyRouting) {
            return false
        }

        val localProxyUrl = "ws://127.0.0.1:8080" + _root_ide_package_.de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository.Routes.PROXY
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