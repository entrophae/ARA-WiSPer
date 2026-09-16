package de.rub.hcd.entrophae.ara_wisper.server.manager

import android.util.Log
import android.util.Base64.decode
import ch.fhnw.pepper_realtime.tools.ToolContext
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import org.json.JSONArray
import org.json.JSONObject
import de.rub.hcd.entrophae.ara_wisper.server.data.PipelineContext
import de.rub.hcd.entrophae.ara_wisper.interceptor.ToolInterceptor
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.createTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.logTurnState
import de.rub.hcd.entrophae.ara_wisper.server.network.LlmClient
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

class ProxyPipelineManager(
    private val serverScope: CoroutineScope,
    private val activeToolContext: ToolContext?,
    private val sendToApp: suspend (String) -> Unit
) {
    val toolInterceptor = ToolInterceptor()

    var apiSupportsToolsTask: Deferred<Boolean>? = null

    var currentSystemPrompt: String = ProxySettingsRepository.customSystemPrompt.ifEmpty { ProxySettingsRepository.defaultSystemPrompt }
    var currentTools: JSONArray? = null
    var isResponseCancelled = false

    var latestItemId = "" // Prevents duplicate chat bubbles if currentTurn is dropped.
    var latestUserText = "" // Lifetime until used by LLM
    var lastKnownUserQuery = "" // Lifetime until new query enters. Reminds LLM the context of the tool-use
    var queryWaitingForTool = "" // Traps the context during tool execution

    val currentUserId = "default"
    var currentTurn: ChatTurn? = null
    var isWaitingForAppToolResult = false

    fun updateIsResponseCancelled(cancelled: Boolean) {
        Log.i(ProxySettingsRepository.TAG, "Interruption received! Cancelling proxy response.")
        this.isResponseCancelled = cancelled
    }

    fun createPipelineContext(responseId: String, systemPrompt: String, tools: JSONArray?, toolsSupported: Boolean): PipelineContext {
        return PipelineContext(
            responseId = responseId,
            systemPrompt = systemPrompt,
            toolsArray = tools,
            apiSupportsTools = toolsSupported,
            toolInterceptor = toolInterceptor,
            activeToolContext = activeToolContext,
            checkCancelled = { isResponseCancelled },
            sendPacket = sendToApp
        )
    }

    fun handleSessionUpdate(json: JSONObject, rawJson: String){
        DashboardManager.sendLog(rawJson, true)

        val sessionObj = json.optJSONObject("session")

        val instructions = sessionObj?.optString("instructions", "")
        if (!instructions.isNullOrEmpty()) {
            // TODO: check if i couldnt add it to proxysettingsrepository and leave it to rest, never touching it here and in pipielinecontext
            currentSystemPrompt = instructions
            Log.i(ProxySettingsRepository.TAG, "Proxy intercepted new system prompt!")
        }

        val tools = sessionObj?.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            toolInterceptor.injectTools(tools)
            // TODO: check if i couldnt add it to proxysettingsrepository and leave it to rest, never touching it here and in pipielinecontext
            currentTools = tools
            val toolNames = mutableListOf<String>()
            for (i in 0 until tools.length()) {
                toolNames.add(tools.getJSONObject(i).optString("name", "unknown"))
            }
            Log.i(ProxySettingsRepository.TAG, "Proxy loaded tools: $toolNames")
        }

        val selectedModel = sessionObj?.optString("model", ProxySettingsRepository.customLlmModel)
        if (!selectedModel.isNullOrEmpty()) {
            apiSupportsToolsTask = serverScope.async {
                LlmClient.checkApiToolSupport(selectedModel)
            }
        }
    }


    fun handleItemCreate(json: JSONObject, rawJson: String){
        DashboardManager.sendLog(rawJson, isFromRobot = true)

        val itemObj = json.optJSONObject(ProxySettingsRepository.Proxy.Keys.ITEM)

        if (itemObj != null && itemObj.optString("type") == "function_call_output") {
            val toolOutput = itemObj.optString("output", "")
            Log.i(ProxySettingsRepository.TAG, "Tool-Ergebnis abgefangen: $toolOutput")

            val combinedQuery = if (lastKnownUserQuery != queryWaitingForTool && queryWaitingForTool.isNotBlank()) {
                // The user asked something new while the tool was running! Combine
                "$queryWaitingForTool\n[Zusätzliche Nutzer-Frage während des Ladens]: $lastKnownUserQuery"
            } else {
                // The user waited. normal query
                lastKnownUserQuery
            }

            latestUserText = """
                ## Ursprüngliche Anfrage: \n$combinedQuery
                ## System-Info (Tool-Ergebnis): \n$toolOutput 
                \nBitte antworte basierend auf diesem Ergebnis auf die ursprüngliche Anfrage
                """.trimIndent()

            queryWaitingForTool = ""
            isWaitingForAppToolResult = false
        } else {
            val text = extractUserText(json)
            val nativeItemId = itemObj?.optString("id", "item_${System.currentTimeMillis()}") ?: ""
            this.currentTurn = createTurn(currentUserId, nativeItemId, text, this.currentTurn)
            latestItemId = nativeItemId
            latestUserText = text
            lastKnownUserQuery = text

            if (text.isNotBlank()) {
                DashboardManager.sendChat(isUser = true, text = text)
            }
        }
    }

    fun handleRespCreate(rawJson: String){
        DashboardManager.sendLog(rawJson, isFromRobot = true)
        this.isResponseCancelled = false;
        updateIsResponseCancelled(false)

        val textToProcess = latestUserText
        if (textToProcess.isNotBlank()) {
            val itemId = this.currentTurn?.turnId ?: latestItemId
            this.currentTurn = createTurn(currentUserId, itemId, textToProcess, this.currentTurn)

            latestUserText = ""

            serverScope.launch {
                executePipeline(audioData = "", initialText = textToProcess, itemId = itemId)
            }
        }
    }

    suspend fun handleToolCall(toolName: String, toolArgs: String?, toolCallId: String, currentContext: String, pipelineContext: PipelineContext, currentTurn: ChatTurn?): Pair<String, Boolean> {
        currentTurn?.toolCalled = toolName
        logTurnState("Tool Called: $toolName", currentTurn)

        val arguments = JSONObject(toolArgs ?: "{}")
        val localResult = pipelineContext.toolInterceptor.attemptExecution(toolName, arguments, pipelineContext.activeToolContext)

        return if (localResult != null) {
            // Tool executed locally, update context and continue loop
            val updatedContext  = """
            Ursprüngliche Anfrage: $currentContext
            System-Info (Tool-Ergebnis für '$toolName'): $localResult 
            Bitte antworte basierend auf diesem Ergebnis.
        """.trimIndent()
            Pair(updatedContext, true)
        } else {
            // Tool not executed locally, send tool call packet and deactivate loop
            pipelineContext.sendPacket(WebSocketPayloads.toolCall(toolCallId, toolName, toolArgs))
            Pair(currentContext, false)
        }
    }

    suspend fun executePipeline(audioData: String, initialText: String, itemId: String) {
        // 1. Audio Transkription
        var textToProcess = initialText
        if (audioData.isNotEmpty() && textToProcess.isBlank()) {

            // TODO: use STT
        }

        // 2. Thinking Notification
        val currentResponseId = "proxy_resp_" + java.util.UUID.randomUUID().toString().substring(0, 8)
        sendToApp(WebSocketPayloads.thinking(currentResponseId))

        // 3. Send output Notification
        sendToApp(WebSocketPayloads.outputItemAdded(itemId + "_assistant"))

        // 4. Loop
        // TODO: Check if toolsupport cannot be moved over to dashboard only when changing models
        val toolsSupported = apiSupportsToolsTask?.await() ?: false
        val pipelineContext = createPipelineContext(currentResponseId, currentSystemPrompt, currentTools, toolsSupported)
        runAgenticLoop(textToProcess, pipelineContext)
    }

    private fun extractUserText(json: JSONObject): String {
        val contentArr = json.optJSONObject(ProxySettingsRepository.Proxy.Keys.ITEM)
            ?.optJSONArray(ProxySettingsRepository.Proxy.Keys.CONTENT)
        if (contentArr != null && contentArr.length() > 0) {
            val text = contentArr.getJSONObject(0).optString(ProxySettingsRepository.Proxy.Keys.TEXT, "")
            Log.i(ProxySettingsRepository.TAG, "Proxy intercepted STT: $text")
            return text
        }
        return ""
    }

    private suspend fun runAgenticLoop(initialContext: String, pipelineContext: PipelineContext) {
        var currentContext = initialContext
        var isPipelineActive = true
        var loopBreaker = 0

        while (isPipelineActive && loopBreaker < 3) {
            loopBreaker++
            if (pipelineContext.checkCancelled()) return

            val llmMessageObj = LlmClient.fetchLlmResponse(currentContext, pipelineContext) ?: return

            val rawContent = llmMessageObj.opt("content")
            var llmText = if (rawContent == null || rawContent == JSONObject.NULL) "" else llmMessageObj.optString("content", "")

            if (LlmClient.toolDetected(llmMessageObj)) {
                val (toolName, toolArgs, cleanedText) = LlmClient.extractToolInformation(llmMessageObj, llmText, pipelineContext)
                llmText = cleanedText
                val toolCallId = "call_${System.currentTimeMillis()}"

                processLlmText(llmText, pipelineContext, isThinkingText = true)

                if (toolName != null) {
                    if (llmText.isNotBlank()) {
                        DashboardManager.sendChat(isUser = false, text = llmText)
                    }
                    val (updatedContext, continueLoop ) =
                        handleToolCall(
                            toolName, toolArgs, toolCallId,
                            currentContext, pipelineContext,
                            this.currentTurn
                        )
                    currentContext = updatedContext
                    if (continueLoop) {
                        Log.i(ProxySettingsRepository.TAG, "Local tool executed. Looping back...")
                        continue
                    } else {
                        isWaitingForAppToolResult = true
                        queryWaitingForTool = lastKnownUserQuery // temporal cache to check if usere said something while llm was thinking tool response
                        isPipelineActive = false
                    }
                } else {
                    pipelineContext.sendPacket(WebSocketPayloads.transcriptDelta(pipelineContext.responseId, "Error"))
                    isPipelineActive = false
                }
            } else {
                processLlmText(llmText, pipelineContext, isThinkingText = false)
                isPipelineActive = false
            }
        }

        pipelineContext.sendPacket(WebSocketPayloads.done())

        if (!isWaitingForAppToolResult) {
            this.currentTurn?.let { turn ->

                // Log.i(ProxySettingsRepository.TAG, "READY FOR DB: ${turn.turnId} | Q: ${turn.userQuery} | A: ${turn.llmResponse} | Tool: ${turn.toolCalled} | ToolBuffer: ${turn.toolBufferResponse}")

                DashboardManager.sendChat(isUser = false, text = turn.llmResponse)

            }
            logTurnState("Finished", this.currentTurn)
        }

    }

    private suspend fun processLlmText(llmText: String, pipelineContext: PipelineContext, isThinkingText: Boolean = false) {
        if (llmText.isNotBlank()) {
            pipelineContext.sendPacket(WebSocketPayloads.transcriptDelta(pipelineContext.responseId, llmText))

            this.currentTurn?.let {
                if(isThinkingText) {
                    it.toolBufferResponse = (it.toolBufferResponse ?: "") + llmText
                } else {
                    it.llmResponse += llmText
                }
                logTurnState("LLM Text Appended", it)
            } ?: Log.w(ProxySettingsRepository.TAG, "Warning: llmText received but currentTurn is null!")
        }
    }

}


