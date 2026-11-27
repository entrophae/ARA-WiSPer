package de.rub.hcd.entrophae.ara_wisper.server.manager

import android.util.Log
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.network.DataBaseClient
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Event bus and broadcaster pushing live telemetry and chat updates to the web dashboard.
 *
 * Emits serialized JSON payloads for user/assistant chat bubbles, image attachments,
 * protocol traffic logs, error alerts, and history refresh triggers.
 */
object DashboardManager {

    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 100)
    val events = _events.asSharedFlow()

    suspend fun broadcastPayload(payload: org.json.JSONObject) {
        // Replace '_events' with whatever your private MutableSharedFlow is named!
        _events.emit(payload)
    }


    fun updateChat(turnId: String, isUser: Boolean, text: String? = null, userImageUrl: String? = null) {
        val message = JSONObject().apply {
            put(ProxySettingsRepository.Logging.Keys.UUID, turnId)
            put(ProxySettingsRepository.Logging.Keys.SENDER, if (isUser) "USER" else "ROBOT")
            if (!text.isNullOrBlank()) {
                put(ProxySettingsRepository.Logging.Keys.MSG, text)
            }
            if (!userImageUrl.isNullOrBlank()) {
                put("userImageUrl", userImageUrl)
            }
        }
        sendDashboardEvent(ProxySettingsRepository.Dashboard.Events.CHAT_UPDATE, JSONArray().put(message))
    }

    fun sendChat(isUser: Boolean, text: String, userImageUrl: String? = null) {
        val message = JSONObject().apply {
            put(ProxySettingsRepository.Logging.Keys.UUID, UUID.randomUUID().toString())
            put(ProxySettingsRepository.Logging.Keys.SENDER, if (isUser) "USER" else "ROBOT")
            put(ProxySettingsRepository.Logging.Keys.MSG, text)
            if (!userImageUrl.isNullOrBlank()) {
                put("userImageUrl", userImageUrl)
            }
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

    suspend fun syncHistoryToBrowser(userId: String) {
        val knowledge = DataBaseClient.getUserKnowledge(userId)
        val history = knowledge.history

        val historyArray = JSONArray()
        for (turn in history) {
            val toolsJsonArray =
                if (turn.toolsUsed.isEmpty()) JSONObject.NULL
                else {
                    JSONArray().apply {
                        for (record in turn.toolsUsed) {
                            put(JSONObject().apply {
                                put("callId", record.callId)
                                put("name", record.name)
                                put("query", record.query)
                                put("answer", record.answer)
                            })
                        }
                    }
                }

            historyArray.put(JSONObject().apply {
                put("turnId", turn.turnId)
                put("userQuery", turn.userQuery)
                put("llmResponse", turn.llmResponse)
                if (!turn.userImageUrl.isNullOrBlank()) {
                    put("userImageUrl", turn.userImageUrl)
                }
                put("toolCalled", turn.toolCalled)
                put("toolBufferResponse", turn.toolBufferResponse)
                put("toolsUsed", toolsJsonArray)
            })
        }

        val syncPacket = JSONObject().apply {
            put(ProxySettingsRepository.Logging.Keys.TYPE, ProxySettingsRepository.Dashboard.Events.CHAT_SYNC)
            put("userId", userId)
            put("chatSummary", knowledge.chatSummary)
            put("history", historyArray)
        }

        _events.tryEmit(syncPacket)
    }

    suspend fun recreateSession(sendToApp: suspend (String) -> Unit) {
        // Fetch the turns from database
        val currentUserId = ProxySettingsRepository.activeUserId
        val savedTurns = DataBaseClient.getUserHistory(currentUserId)

        for (turn in savedTurns) {
            // Recreate User message
            val userPayload = WebSocketPayloads.itemCreateUser(turn.turnId, turn.userQuery)
            sendToApp( userPayload )
            sendChat(isUser = true, text = turn.userQuery,
                userImageUrl = turn.userImageUrl)

            // Recreate LLM message
            val llmPayload = WebSocketPayloads.itemCreateLLM(turn.turnId + "_llm", turn.llmResponse)
            sendToApp(llmPayload)
            sendChat(isUser = false, text = turn.llmResponse)

        }
    }

}

suspend fun DefaultWebSocketServerSession.sendToApp(jsonString: String) {
    send(Frame.Text(jsonString))
    DashboardManager.sendLog(jsonString, false)
}