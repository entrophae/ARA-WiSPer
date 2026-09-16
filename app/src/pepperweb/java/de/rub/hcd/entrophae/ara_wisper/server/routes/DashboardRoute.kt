package de.rub.hcd.entrophae.ara_wisper.server.routes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.network.HttpClientManager
import ch.fhnw.pepper_realtime.ui.ChatMessage
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import com.google.gson.Gson
import de.rub.hcd.entrophae.ara_wisper.core.ApiDataTemplate
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager.appPrefs
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager.appString
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository.Settings
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import de.rub.hcd.entrophae.ara_wisper.server.manager.DashboardManager
import de.rub.hcd.entrophae.ara_wisper.server.network.DataBaseClient
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import de.rub.hcd.entrophae.ara_wisper.server.network.authBearer
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

val probeText = "ARAW1SP3R"

fun Route.dashboardRouting(viewModel: ChatViewModel) {

    webSocket(ProxySettingsRepository.Routes.WS) {
        Log.i(appString(R.string.app_name), "Dashboard connected!")

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
            Settings.VLM_MODEL -> ProxySettingsRepository.customVlmEndpoint
            Settings.EMBEDDING_MODEL -> ProxySettingsRepository.customEmbeddingEndpoint
            else -> ProxySettingsRepository.customLlmEndpoint
        }
        val apiKey = when (type) {
            Settings.VLM_MODEL -> ProxySettingsRepository.customVlmKey
            Settings.EMBEDDING_MODEL -> ProxySettingsRepository.customEmbeddingKey
            else -> ProxySettingsRepository.customLlmKey
        }

        val targetUrl = "$endpoint/models"

        if (endpoint.isEmpty() || apiKey.isEmpty()) {
            call.respondText("""{"error": "Missing Endpoint/apiKey"}""", ContentType.Application.Json)
            Log.e("AraWisper", "Custom API Request Failed")
            return@get
        }

        val request = Request.Builder().url(targetUrl).authBearer(apiKey).get().build()

        try {
            val client = HttpClientManager.getInstance().getQuickApiClient()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "[]"
                call.respondText(body, ContentType.Application.Json)
            }
        } catch (e: Exception) {
            call.respondText("""{"error": "${e.message}"}""", ContentType.Application.Json)
        }
    }

    get(ProxySettingsRepository.Routes.PROXY_PROBE) {
        val modelId = call.request.queryParameters["modelId"] ?: ""
        val probeType = call.request.queryParameters["probeType"] ?: "vision"
        val fieldId = call.request.queryParameters["fieldId"] ?: ""

        val endpoint = when (fieldId) {
            Settings.VLM_MODEL -> ProxySettingsRepository.customVlmEndpoint
            Settings.EMBEDDING_MODEL -> ProxySettingsRepository.customEmbeddingEndpoint
            else -> ProxySettingsRepository.customLlmEndpoint
        }
        val apiKey = when (fieldId) {
            Settings.VLM_MODEL -> ProxySettingsRepository.customVlmKey
            Settings.EMBEDDING_MODEL -> ProxySettingsRepository.customEmbeddingKey
            else -> ProxySettingsRepository.customLlmKey
        }

        if (endpoint.isBlank() || apiKey.isBlank()) {
            call.respondText("""{"supported": false, "response": "endpoint empty"}""", ContentType.Application.Json)
            return@get
        }

        val targetUrl = if (probeType == "embed") "$endpoint/embeddings" else "$endpoint/chat/completions"

        val requestBodyJson = JSONObject().apply {
            put("model", modelId)
            when (probeType) {
                "vision" -> {
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Read the nine characters in the image. Reply with only those nine characters")
                        }).put(JSONObject().apply {
                            put("type", "image_url")
                            put(
                                "image_url",
                                JSONObject().put(
                                    "url",
                                    createVisionProbeImage(probeText)
                                )
                            )
                        }))
                    }))
                }
                "embed" -> {
                    put("input", "test")
                }
                "tools" -> {
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", "ping")
                    }))
                    put("tools", "tools-not-array")
                }
            }
        }

        val reqBody = requestBodyJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(targetUrl).authBearer(apiKey).post(reqBody).build()

        try {
            val client = HttpClientManager.getInstance().getQuickApiClient()
            client.newCall(request).execute().use { response ->
                var errorString: String? = ""
                val responseBodyStr = response.body?.string() ?: ""
                if (response.code == 404 || response.code == 405) {
                    val resultJson = JSONObject().apply {
                        put("supported", false)
                        put("response", responseBodyStr)
                    }
                    call.respondText(resultJson.toString(), ContentType.Application.Json)
                    return@get
                }
                val isSupported = when (probeType) {
                    "tools" ->
                        response.code == 400 && (responseBodyStr.lowercase().contains("tools") || responseBodyStr.lowercase().contains("param"))
                    "vision" ->
                        if (!response.isSuccessful) {
                            false
                        } else {
                            try {
                                val answer = JSONObject(responseBodyStr)
                                    .getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .optString("content")
                                    .trim()

                                answer.length == probeText.length &&
                                    answer.zip(probeText).count { (a, b) ->
                                        a.uppercaseChar() != b.uppercaseChar()
                                    } <= ceil((probeText.length / 2).toDouble())
                            } catch (e: Exception) {
                                errorString = e.message;
                                false
                            }
                        }
                    "embed" -> response.isSuccessful && !responseBodyStr.contains("\"error\"")
                    else -> response.isSuccessful
                }
                val resultJson = JSONObject().apply {
                    put("supported", isSupported)
                    put("response", responseBodyStr)
                    if (errorString?.isNotBlank() == true) {
                        put("error", errorString)
                    }
                }
                call.respondText(resultJson.toString(), ContentType.Application.Json)
            }
        } catch (e: Exception) {
            val resultJson = JSONObject().apply {
                put("supported", false)
                put("error", e.message)
            }
            call.respondText(resultJson.toString(), ContentType.Application.Json)
        }
    }


}

private suspend fun DefaultWebSocketServerSession.sendInitialApiKeys() {
    val dataObj = JSONObject().apply {
        ApiDataTemplate.allSettings.forEach { setting ->
            put(setting.id, ProxySettingsRepository.getPref(setting.id, setting.default))
        }
    }
    val schemaObj = JSONObject().apply {
        // put("providers", ApiDataTemplate.Providers.toUiSchema()) // Not used cause we use custom llms here
        put("proxy", ApiDataTemplate.Proxy.toUiSchema())
        put("database", ApiDataTemplate.Database.toUiSchema())
        put("tools", ApiDataTemplate.Tools.toUiSchema())
        put("system", ApiDataTemplate.System.toUiSchema())
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
                processSaveKeys(json.getJSONObject("data"), viewModel)
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

    if (apiMode == ProxySettingsRepository.Modes.ROUTING.CUSTOM) {
        val userMsg = ChatMessage(text, ChatMessage.Sender.USER)
        viewModel.addMessage(userMsg)

        viewModel.sendMessageToRealtimeAPI(text, requestResponse = true, allowInterrupt = false)
    } else {
        viewModel.sendMessageToRealtimeAPI(text, requestResponse = true, allowInterrupt = true)
    }
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
            val turn = ChatTurn(
                userId = userId,
                turnId = turnObj.optString("turnId"),
                userQuery = turnObj.optString("userQuery"),
                llmResponse = turnObj.optString("llmResponse"),
                toolCalled = if (turnObj.isNull("toolCalled")) null else turnObj.optString("toolCalled"),
                toolBufferResponse = if (turnObj.isNull("toolBufferResponse")) null else turnObj.optString("toolBufferResponse")
            )
            DataBaseClient.updateHistory(turn)
        }
    }
    DashboardManager.syncHistoryToBrowser(userId)
}

private suspend fun DefaultWebSocketServerSession.processSaveKeys(data: JSONObject, viewModel: ChatViewModel) {
    appPrefs.edit {
        ApiDataTemplate.allSettings.forEach { setting ->
            val value = data.optString(setting.id, setting.default)
            if (value.isNotBlank()) putString(setting.id, value) else remove(setting.id)
        }
    }
    viewModel.startNewSession()

    val successMsg = WebSocketPayloads.dashboardPayload(ProxySettingsRepository.Dashboard.Events.STATUS_UPDATE,"API Keys Saved! Restarting session...")
    sendJsonToClient(successMsg)
}

private suspend fun DefaultWebSocketServerSession.sendJsonToClient(payload: JSONObject) {
    try {
        send(Frame.Text(payload.toString()))
    } catch (e: Exception) {
        Log.w("AraWisper", "Failed to send data to dashboard", e)
    }
}

private fun createVisionProbeImage(text: String): String {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 50F
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }
    val bounds = android.graphics.Rect()
    paint.getTextBounds(text, 0, text.length, bounds)

    val paddingX = 60
    val paddingY = 40
    val width = bounds.width() + paddingX
    val height = bounds.height() + paddingY

    val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    canvas.drawColor(Color.WHITE)

    val x = (paddingX / 2f) - bounds.left
    val y = (height / 2f) + (bounds.height() / 2f) - bounds.bottom

    canvas.drawText(text, x, y, paint)

    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
    bitmap.recycle()

    val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    return "data:image/png;base64,$base64"
}

