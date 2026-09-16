package de.rub.hcd.entrophae.ara_wisper.server.manager

import android.util.Log
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object DashboardManager {

    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 100)
    val events = _events.asSharedFlow()

    fun sendChat(isUser: Boolean, text: String) {
        val message = JSONObject().apply {
            put(ProxySettingsRepository.Logging.Keys.UUID, UUID.randomUUID().toString())
            put(ProxySettingsRepository.Logging.Keys.SENDER, if (isUser) "USER" else "ROBOT")
            put(ProxySettingsRepository.Logging.Keys.MSG, text)
        }
        sendDashboardEvent(ProxySettingsRepository.Dashboard.Events.CHAT_UPDATE, JSONArray().put(message))
    }

    fun sendLog(jsonString: String, isFromRobot: Boolean, isError: Boolean = false) {
        try {
            var direction =
                if (isError) ProxySettingsRepository.Logging.ERROR
                else if (isFromRobot) ProxySettingsRepository.Logging.Direction.RTP
                else ProxySettingsRepository.Logging.Direction.PTR
            if (isError) direction = ProxySettingsRepository.Logging.ERROR

            val logPayload = JSONObject().apply {
                put(ProxySettingsRepository.Logging.Keys.TYPE, ProxySettingsRepository.Logging.TYPE_PROXY_LOG)
                put(ProxySettingsRepository.Logging.Keys.DIRECTION, direction)
                put(ProxySettingsRepository.Logging.Keys.PAYLOAD, JSONObject(jsonString))
            }
            _events.tryEmit(logPayload)
        } catch (e: Exception) {
            Log.w(ProxySettingsRepository.TAG, "Failed to parse proxy log")
        }
    }

    fun sendError(text: String) {
        sendLog(
            JSONObject().put(ProxySettingsRepository.Logging.Keys.ERROR, text).toString(),
            isFromRobot = false, isError = true
        )
    }

    fun sendDashboardEvent(type: String, data: Any? = null) {
        val payload = JSONObject().apply {
            put(ProxySettingsRepository.Logging.Keys.TYPE, type)
            if (data != null) put(ProxySettingsRepository.Logging.Keys.DATA, data)
        }
        _events.tryEmit(payload)
    }

}

suspend fun DefaultWebSocketServerSession.sendToApp(jsonString: String) {
    send(Frame.Text(jsonString))
    DashboardManager.sendLog(jsonString, false)
}