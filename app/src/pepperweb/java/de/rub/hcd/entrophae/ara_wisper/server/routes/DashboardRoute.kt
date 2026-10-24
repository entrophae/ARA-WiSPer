package de.rub.hcd.entrophae.ara_wisper.server.routes

import android.util.Log
import androidx.core.content.edit
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.ui.ChatMessage
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import com.google.gson.Gson
import de.rub.hcd.entrophae.ara_wisper.core.ApiDataTemplate
import de.rub.hcd.entrophae.ara_wisper.core.ChatActivityAdapter
import de.rub.hcd.entrophae.ara_wisper.core.SettingsAdapter
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository.Settings
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.ToolCallRecord
import de.rub.hcd.entrophae.ara_wisper.server.manager.DashboardManager
import de.rub.hcd.entrophae.ara_wisper.server.network.DataBaseClient
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import de.rub.hcd.entrophae.ara_wisper.server.network.LlmClient
import de.rub.hcd.entrophae.ara_wisper.server.network.RagClient
import de.rub.hcd.entrophae.ara_wisper.server.network.SttClient

fun Route.dashboardRouting(viewModel: ChatViewModel) {

    webSocket(ProxySettingsRepository.Routes.WS) {
        Log.i(AraWisperManager.appString(R.string.app_name), "Dashboard connected!")

        sendJsonToClient(JSONObject().apply{
                put("status", "connected")
                put("message", "ARA-WiSPer Core Active")
            }
        )

        sendInitialApiKeys()

        val statusJob = launch { syncRobotStatus(viewModel) }
        // val chatJob = launch { syncChatHistory(viewModel) }
        val loggerJob = launch {
            DashboardManager.events.collect { payload ->
                sendJsonToClient(payload)
            }
        }

        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val rawJson = frame.readText()
                    DashboardManager.sendLog(rawJson, isFromRobot = true)
                    handleDashboardCommand(rawJson, viewModel)
                }
            }
        } catch (e: Exception) {
            DashboardManager.sendError("WebSocket connection error: ${e.message}")
        } finally {
            statusJob.cancel()
            // chatJob.cancel()
            loggerJob.cancel()
        }
    }

    get(ProxySettingsRepository.Routes.PROXY_MODELS) {
        val type = call.request.queryParameters["type"]

        val endpoint = when (type) {
            Settings.VLM_CONFIG -> ProxySettingsRepository.customVlmEndpoint
            Settings.EMBEDDING_CONFIG -> ProxySettingsRepository.customEmbeddingEndpoint
            else -> ProxySettingsRepository.customLlmEndpoint
        }
        val apiKey = when (type) {
            Settings.VLM_CONFIG -> ProxySettingsRepository.customVlmKey
            Settings.EMBEDDING_CONFIG -> ProxySettingsRepository.customEmbeddingKey
            else -> ProxySettingsRepository.customLlmKey
        }

        val result = LlmClient.getAvailableModels(endpoint, apiKey)
        call.respondText(result, ContentType.Application.Json)
    }

    get(ProxySettingsRepository.Routes.PROXY_PROBE) {
        val modelId = call.request.queryParameters["modelId"] ?: ""
        val probeType = call.request.queryParameters["probeType"] ?: "vision"
        val fieldId = call.request.queryParameters["fieldId"] ?: ""

        val endpoint = when (fieldId) {
            Settings.VLM_CONFIG -> ProxySettingsRepository.customVlmEndpoint
            Settings.EMBEDDING_CONFIG -> ProxySettingsRepository.customEmbeddingEndpoint
            else -> ProxySettingsRepository.customLlmEndpoint
        }
        val apiKey = when (fieldId) {
            Settings.VLM_CONFIG -> ProxySettingsRepository.customVlmKey
            Settings.EMBEDDING_CONFIG -> ProxySettingsRepository.customEmbeddingKey
            else -> ProxySettingsRepository.customLlmKey
        }

        val (isSupported, responseText) = LlmClient.probeModelCapability(
            modelId, probeType, endpoint, apiKey
        )
        val resultJson = JSONObject().apply {
            put("supported", isSupported)
            put("response", responseText)
        }
        call.respondText(resultJson.toString(), ContentType.Application.Json)
    }

    get(ProxySettingsRepository.Routes.STATUS_DB) {
        try {
            val result = DataBaseClient.fetchStatus()
            if (result.contains(""""status":"error"""")) {
                call.respondText(result, ContentType.Application.Json, io.ktor.http.HttpStatusCode.ServiceUnavailable)
            } else {
                call.respondText(result, ContentType.Application.Json)
            }
        } catch (e: Exception) {
            call.respondText(
                """{"status": "offline", "error": "${e.message}"}""",
                ContentType.Application.Json,
                io.ktor.http.HttpStatusCode.ServiceUnavailable
            )
        }
    }

    get(ProxySettingsRepository.Routes.STATUS_RAG) {
        try {
            val result = RagClient.fetchStatus()
            if (result.contains(""""status":"error"""")) {
                call.respondText(result, ContentType.Application.Json, io.ktor.http.HttpStatusCode.ServiceUnavailable)
            } else {
                call.respondText(result, ContentType.Application.Json)
            }
        } catch (e: Exception) {
            call.respondText(
                """{"status": "offline", "error": "${e.message}"}""",
                ContentType.Application.Json,
                io.ktor.http.HttpStatusCode.ServiceUnavailable
            )
        }
    }

    get(ProxySettingsRepository.Routes.STATUS_STT) {
        try {
            val result = SttClient.fetchStatus()
            if (result.contains(""""status":"error"""")) {
                call.respondText(result, ContentType.Application.Json, io.ktor.http.HttpStatusCode.ServiceUnavailable)
            } else {
                call.respondText(result, ContentType.Application.Json)
            }
        } catch (e: Exception) {
            call.respondText(
                """{"status": "offline", "error": "${e.message}"}""",
                ContentType.Application.Json,
                io.ktor.http.HttpStatusCode.ServiceUnavailable
            )
        }
    }
}

private suspend fun DefaultWebSocketServerSession.sendInitialApiKeys() {
    val prefs = AraWisperManager.appPrefs

    val activity = AraWisperManager.currentChatActivity
    val adapter = activity?.let { ChatActivityAdapter(it) }

    val dataObj = SettingsAdapter.readAllAsJson(prefs, adapter)

    val schemaObj = JSONObject().apply {
        put("models", ApiDataTemplate.Models.toUiSchema())
        put("database", ApiDataTemplate.Database.toUiSchema())
        put("speech",ApiDataTemplate.Speech.toUiSchema())
        put("tools", ApiDataTemplate.Tools.toUiSchema())
        put("system", ApiDataTemplate.System.toUiSchema())
        put("providers", ApiDataTemplate.Providers.toUiSchema())
        put("azure", ApiDataTemplate.Azure.toUiSchema())
        put("appSettings", ApiDataTemplate.buildAppDynamicSchema(AraWisperManager.appContext))
    }

    val currentKeys = WebSocketPayloads.dashboardPayload(
        ProxySettingsRepository.Dashboard.Events.LOAD_API_KEYS,
        dataObj
    )
    currentKeys.apply { put("schema", schemaObj) }

    sendJsonToClient(currentKeys)
}

private suspend fun DefaultWebSocketServerSession.syncRobotStatus(viewModel: ChatViewModel) {
    viewModel.statusText.collect { status ->
        val payload = WebSocketPayloads.dashboardPayload(
            ProxySettingsRepository.Dashboard.Events.STATUS_UPDATE,
            status)
        sendJsonToClient(payload)
    }
}

private suspend fun DefaultWebSocketServerSession.syncChatHistory(viewModel: ChatViewModel) {
    viewModel.messageList.collect { messages ->
        val payload = WebSocketPayloads.dashboardPayload(
            ProxySettingsRepository.Dashboard.Events.CHAT_UPDATE,
            JSONArray(Gson().toJson(messages)))
        sendJsonToClient(payload)
    }
}

private suspend fun DefaultWebSocketServerSession.handleDashboardCommand(rawJson: String, viewModel: ChatViewModel) {
    try {
        val json = JSONObject(rawJson)
        val type = json.optString(ProxySettingsRepository.Proxy.Keys.TYPE)
        when (type) {
            ProxySettingsRepository.Dashboard.Events.SAVE_API_KEYS -> {
                processSaveKeys(json, viewModel)
            }
            ProxySettingsRepository.Dashboard.Events.CHAT_COMMAND -> {
                processChatCommand(json, viewModel)
            }
            ProxySettingsRepository.Dashboard.Events.SET_USER -> {
                processSetUser(json, viewModel)
            }
            ProxySettingsRepository.Dashboard.Events.UPDATE_USER_KNOWLEDGE -> {
                processUpdateUserKnowledge(json, viewModel)
            }
            ProxySettingsRepository.Dashboard.Events.DELETE_USER -> {
                processDeleteUser(json, viewModel)
            }
            ProxySettingsRepository.Dashboard.Events.CLEAR_HISTORY -> {
                processClearHistory(json, viewModel)
            }
            ProxySettingsRepository.Dashboard.Events.TOGGLE_MUTE -> {
                processToggleMute(json)
            }
            ProxySettingsRepository.Dashboard.Events.RESTART -> {
                processRestart()
            }
            ProxySettingsRepository.Dashboard.Events.INTERRUPT -> {
                processInterrupt()
            }

            else -> {
                DashboardManager.sendError("Unknown command type received")
            }
        }
    } catch (e: Exception) {
        DashboardManager.sendError("Invalid JSON received from Dashboard: ${e.message}")
    }
}
private fun processChatCommand(json: JSONObject, viewModel: ChatViewModel) {
    val apiMode = json.optString("apiMode")
    val text = json.optString("text")

    val taggedText = "\u200B$text"

    if (apiMode == ProxySettingsRepository.Modes.ROUTING.CUSTOM) {
        val userMsg = ChatMessage(text, ChatMessage.Sender.USER)
        viewModel.addMessage(userMsg)

        viewModel.sendMessageToRealtimeAPI(taggedText, requestResponse = true, allowInterrupt = false)
    } else {
        viewModel.sendMessageToRealtimeAPI(taggedText, requestResponse = true, allowInterrupt = true)
    }
}
private fun processToggleMute(json: JSONObject) {
    val activity = AraWisperManager.currentChatActivity
    val adapter = activity?.let { ChatActivityAdapter(it) }

    val userWantsMicOn = json.optBoolean("data", false)
    AraWisperManager.appPrefs.edit {
        putBoolean(Settings.MIC_STATUS, userWantsMicOn)
    }

    adapter?.toggleMicrophone()
}

private suspend fun processSetUser(json: JSONObject, viewModel: ChatViewModel) {
    val newUserId = json.optString("userId", "default_user")
    ProxySettingsRepository.activeUserId = newUserId
    DataBaseClient.addUser(newUserId)

    viewModel.startNewSession()
    Log.i(ProxySettingsRepository.TAG, "Active User changed to: $newUserId")
    DashboardManager.syncHistoryToBrowser(newUserId)
}
private suspend fun processDeleteUser(json: JSONObject, viewModel: ChatViewModel) {
    val userId = json.optString("userId")
    if (userId.isNotEmpty() && userId != "default_user") {
        DataBaseClient.deleteUser(userId)
        val newUserId = "default_user"
        ProxySettingsRepository.activeUserId = newUserId

        viewModel.startNewSession()
        Log.i(ProxySettingsRepository.TAG, "Active User changed to: $newUserId")
        DashboardManager.syncHistoryToBrowser(newUserId)
    }
}
private suspend fun processClearHistory(json: JSONObject, viewModel: ChatViewModel) {
    val userId = json.optString("userId")
    if (userId.isNotEmpty()) {
        DataBaseClient.deleteHistory(userId)
        viewModel.startNewSession()
        Log.i(ProxySettingsRepository.TAG, "Chat History cleared: $userId")
        DashboardManager.syncHistoryToBrowser(userId)
    }
}

private suspend fun processUpdateUserKnowledge(json: JSONObject, viewModel: ChatViewModel) {
    val userId = json.optString("userId")
    if (userId.isEmpty()) return

    Log.i("AraWisper", "Processing WebSocket knowledge update for: $userId")

    val summary = json.optString("summary")
    DataBaseClient.updateSummary(userId, summary)

    val deletedTurns = json.optJSONArray("deletedTurnIds")
    if (deletedTurns != null) {
        for (i in 0 until deletedTurns.length()) {
            val turnId = deletedTurns.getString(i)
            DataBaseClient.deleteTurn(userId, turnId)
        }
    }

    val updatedTurns = json.optJSONArray("updatedTurns")
    if (updatedTurns != null) {
        for (i in 0 until updatedTurns.length()) {
            val turnObj = updatedTurns.getJSONObject(i)
            val toolsUsedList = mutableListOf<ToolCallRecord>()
            val toolsArray = turnObj.optJSONArray("toolsUsed")
            if (toolsArray != null) {
                for (j in 0 until toolsArray.length()) {
                    val tObj = toolsArray.getJSONObject(j)
                    toolsUsedList.add(
                        ToolCallRecord(
                            callId = tObj.optString("callId", ""),
                            name = tObj.optString("name", ""),
                            query = tObj.optString("query", ""),
                            answer = tObj.optString("answer", "")
                        )
                    )
                }
            }
            val turn = ChatTurn(
                userId = userId,
                turnId = turnObj.optString("turnId"),
                userQuery = turnObj.optString("userQuery"),
                userImageUrl = if (turnObj.isNull("userImageUrl")) null else turnObj.optString("userImageUrl"),
                llmResponse = turnObj.optString("llmResponse"),
                toolCalled = if (turnObj.isNull("toolCalled")) null else turnObj.optString("toolCalled"),
                toolBufferResponse = if (turnObj.isNull("toolBufferResponse")) null else turnObj.optString("toolBufferResponse"),
                toolsUsed = toolsUsedList
            )
            DataBaseClient.updateHistory(turn)
        }
    }
    DashboardManager.syncHistoryToBrowser(userId)
}

private suspend fun DefaultWebSocketServerSession.processSaveKeys(json: JSONObject, viewModel: ChatViewModel) {
    val data = json.getJSONObject("data")
    val prefs = AraWisperManager.appPrefs
    val activity = AraWisperManager.currentChatActivity
    val adapter = activity?.let { ChatActivityAdapter(it) }

    SettingsAdapter.saveJsonToPrefs(prefs, data, adapter)

    val successMsg = WebSocketPayloads.dashboardPayload(ProxySettingsRepository.Dashboard.Events.STATUS_UPDATE,"Settings saved!")
    sendJsonToClient(successMsg)
}

private suspend fun DefaultWebSocketServerSession.processRestart() {
    val activity = AraWisperManager.currentChatActivity
    val adapter = activity?.let { ChatActivityAdapter(it) }

    adapter?.restartSession()

    val successMsg = WebSocketPayloads.dashboardPayload(ProxySettingsRepository.Dashboard.Events.STATUS_UPDATE,"Restarting connection")
    sendJsonToClient(successMsg)
}
private suspend fun DefaultWebSocketServerSession.processInterrupt() {
    val activity = AraWisperManager.currentChatActivity
    val adapter = activity?.let { ChatActivityAdapter(it) }

    adapter?.interruptSpeech()

    val successMsg = WebSocketPayloads.dashboardPayload(ProxySettingsRepository.Dashboard.Events.STATUS_UPDATE,"Speech interrupted")
    sendJsonToClient(successMsg)
}


suspend fun DefaultWebSocketServerSession.sendJsonToClient(payload: JSONObject) {
    try {
        send(Frame.Text(payload.toString()))
    } catch (e: Exception) {
        Log.w("AraWisper", "Failed to send data to dashboard", e)
    }
}
