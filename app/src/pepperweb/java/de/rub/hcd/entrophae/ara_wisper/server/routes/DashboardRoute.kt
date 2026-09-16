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
