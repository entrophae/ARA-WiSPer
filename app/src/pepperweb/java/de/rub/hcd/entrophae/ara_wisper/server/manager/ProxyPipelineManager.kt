package de.rub.hcd.entrophae.ara_wisper.server.manager

import android.util.Base64
import android.util.Log
import android.util.Base64.decode
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import org.json.JSONObject
import de.rub.hcd.entrophae.ara_wisper.server.data.PipelineContext
import de.rub.hcd.entrophae.ara_wisper.interceptor.ToolInterceptor
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.server.network.DataBaseClient
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.createPipelineContext
import de.rub.hcd.entrophae.ara_wisper.server.data.logTurnState
import de.rub.hcd.entrophae.ara_wisper.server.network.LlmClient
import de.rub.hcd.entrophae.ara_wisper.server.network.SttClient
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import de.rub.hcd.entrophae.ara_wisper.server.service.AudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ProxyPipelineManager(
    private val serverScope: CoroutineScope,
    private val sendToApp: suspend (String) -> Unit
) {
    val toolInterceptor = ToolInterceptor()
    val audioProcessor = AudioProcessor()

    val currentUserId: String
        get() = ProxySettingsRepository.activeUserId

    var currentTurn: ChatTurn? = null
    var isResponseCancelled = false

    var queryWaitingForTool = "" // Traps the context during tool execution
    var isWaitingForAppToolResult = false
    var isHoldingToolResult = false // Protects tool context from late tool system messages

    @Volatile
    var isPipelineRunning = false

    var latestUserText = "" // Lifetime until used by LLM

    fun handleSessionUpdate(json: JSONObject, rawJson: String){
        DashboardManager.sendLog(rawJson, true)

        val sessionObj = json.optJSONObject("session")

        val instructions = sessionObj?.optString("instructions", "")
        if (!instructions.isNullOrEmpty()) {
            ProxySettingsRepository.customSystemPrompt = instructions
            Log.i(ProxySettingsRepository.TAG, "Proxy intercepted new system prompt!")
        }

        val tools = sessionObj?.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            toolInterceptor.injectTools(tools)
            ProxySettingsRepository.currentTools = tools
            val toolNames = mutableListOf<String>()
            for (i in 0 until tools.length()) {
                toolNames.add(tools.getJSONObject(i).optString("name", "unknown"))
            }
            Log.i(ProxySettingsRepository.TAG, "Proxy loaded tools: $toolNames")
        }

        audioProcessor.reconfigure()
    }

    fun handleAudioAppend(json: JSONObject){
        // AraWisperManager.applyMuteState()
        if (AraWisperManager.chatActivityAdapter?.audioInputController?.isMuted == true) {
            return
        }

        val audioData = json.optString(ProxySettingsRepository.Proxy.Keys.AUDIO)
        if (audioData.isBlank()) return
        try {
            val rawBytes = decode(audioData, Base64.NO_WRAP)
            processAudio(rawBytes)
        } catch (e: IllegalArgumentException) {
            Log.e(ProxySettingsRepository.TAG, "Failed to decode audio base64", e)
        }
    }

    fun handleItemCreate(json: JSONObject, rawJson: String){
        DashboardManager.sendLog(rawJson, isFromRobot = true)

        val itemObj = json.optJSONObject(ProxySettingsRepository.Proxy.Keys.ITEM) ?: return

        // Route Tool Outputs (from async/external tools)
        if (itemObj.optString("type") == "function_call_output") {
            processToolOutputItem(itemObj)
            return
        }

        // Extract standard user payload (Text & Images)
        val (rawText, imageUrl) = WebSocketPayloads.extractUserPayloads(json)

        // Route Image handling
        if (!imageUrl.isNullOrBlank()) {
            processImageItem(imageUrl)
        }
        if (rawText == null) return

        val nativeItemId = itemObj.optString("id", "item_${System.currentTimeMillis()}")
        val isManualCommand = rawText.startsWith("\u200B")
        val cleanText = rawText.removePrefix("\u200B")

        // Route based on text source
        if (isManualCommand) {
            processManualCommandItem(cleanText, nativeItemId)
        } else {
            processSystemEventItem(cleanText, nativeItemId)
        }
    }

    fun handleRespCreate(rawJson: String){
        DashboardManager.sendLog(rawJson, isFromRobot = true)
        this.isResponseCancelled = false;
        updateIsResponseCancelled(false)
        // AraWisperManager.speechManager.stop()

        val textToProcess = latestUserText
        if (textToProcess.isNotEmpty()) {
            val itemId = this.currentTurn?.turnId ?: ""
            this.currentTurn = ChatTurn.resolve(currentTurn, currentUserId, itemId, textToProcess )

            latestUserText = ""
            audioProcessor.reset()
            this.currentTurn?.audioBuffer?.reset()

            serverScope.launch {
                executePipeline(audioData = "", initialText = textToProcess, itemId = itemId)
            }
        }
    }

    fun handleItemTruncated(json: JSONObject) {
        val audioEndMs = json.optInt("audio_end_ms", 0)
        if (audioEndMs <= 0) return

        serverScope.launch {
            SttClient.handleAudioTruncation(currentTurn, audioEndMs, audioProcessor)
        }
    }

    private fun processToolOutputItem(itemObj: JSONObject) {
        val toolCallId = itemObj.optString("call_id", itemObj.optString("tool_call_id", ""))
        val toolOutput = itemObj.optString("output", "")
        val toolAnswer = if (toolOutput.isNotBlank()) {
            try {
                val parsed = JSONObject(toolOutput)
                if (parsed.has("answer")) parsed.getString("answer") else toolOutput
            } catch (_: Exception) {
                toolOutput
            }
        } else ""

        Log.i(ProxySettingsRepository.TAG, "Tool-Ergebnis abgefangen: $toolAnswer \n\n")

        currentTurn?.addToolAnswer(toolCallId, toolAnswer)

        val activeQuery = currentTurn?.userQuery ?: ""
        latestUserText = toolInterceptor.formatToolOutputPrompt(activeQuery, queryWaitingForTool, currentTurn?.userImageUrl, toolAnswer)

        queryWaitingForTool = ""
        isWaitingForAppToolResult = false
        isHoldingToolResult = true // Protect this context until the LLM reads it
    }

    private fun processImageItem(imageUrl: String) {
        Log.i(ProxySettingsRepository.TAG, "Proxy intercepted image payload! Caching for VLM.")

        this.currentTurn?.let { turn ->
            turn.userImageUrl = imageUrl
            // Instantly push this image to the dashboard live log for this item ID
            DashboardManager.updateChat(turnId = turn.turnId, isUser = true, userImageUrl = imageUrl)
        }

        AraWisperManager.dashboardVideoManager.restartCameraStream()
    }

    private fun processManualCommandItem(cleanText: String, nativeItemId: String) {
        isHoldingToolResult = false
        this.currentTurn = ChatTurn.resolve(currentTurn, currentUserId, nativeItemId, cleanText)

        latestUserText = cleanText

        if (cleanText.isNotBlank()) {
            DashboardManager.sendChat(
                isUser = true,
                text = cleanText,
                userImageUrl = this.currentTurn?.userImageUrl
            )
        }
    }

    private fun processSystemEventItem(cleanText: String, nativeItemId: String) {
        // Asynchronous System Event (like "[MOVEMENT COMPLETED]" or "📷 Opening Camera")
        Log.i(ProxySettingsRepository.TAG, "Proxy intercepted System Event: $cleanText")

        // Drop late system noise to protect active tool context
        if (isHoldingToolResult) {
            Log.i(ProxySettingsRepository.TAG, "Dropped system noise to protect active tool context.")
            return
        }

        // Drop notification sent during single-phase tool execution
        if (isWaitingForAppToolResult) return

        latestUserText = """
            [INTERNES SYSTEM-UPDATE]:
            $cleanText
            
            Beziehe diese Information in deine nächste Antwort ein.
        """.trimIndent()

        // Append to current turn if LLM hasn't responded yet, otherwise create a new turn
        if (this.currentTurn == null || this.currentTurn?.llmResponse?.isNotEmpty() == true) {
            this.currentTurn = ChatTurn.resolve(this.currentTurn, currentUserId, nativeItemId, cleanText, isSystem = true)
        } else {
            Log.i(ProxySettingsRepository.TAG, "Appended system event to ongoing turn. Preserving DB save state.")
        }

        DashboardManager.sendChat(
            isUser = true,
            text = "⚙️ $cleanText",
            userImageUrl = this.currentTurn?.userImageUrl
        )
    }


    suspend fun executePipeline(audioData: String, initialText: String, itemId: String) {
        val currentResponseId = "proxy_resp_" + java.util.UUID.randomUUID().toString().substring(0, 8)
        val pipelineContext = createPipelineContext(currentResponseId, sendToApp)

        // Prepare Text
        val textToProcess = preparePipelineInput(audioData, initialText, itemId, pipelineContext)
        if (textToProcess.isBlank()) return

        // Notify App
        pipelineContext.sendThinkingPacket()
        pipelineContext.sendOutputItemAdded(itemId + "_assistant")

        // Start AI Loop
        runAgenticLoop(textToProcess, pipelineContext)
    }

    private suspend fun preparePipelineInput(audioData: String, initialText: String, itemId: String, pipelineContext: PipelineContext): String {
        // If we already have text, just return it
        if (audioData.isEmpty() || initialText.isNotBlank()) return initialText

        // Otherwise, process audio transcription with an empty turn being initiated first
        this.currentTurn = ChatTurn.resolve(currentTurn, currentUserId, itemId, initialText)
        sendToApp(WebSocketPayloads.userItemPlaceholder(itemId))

        val transcribedText = SttClient.sendAudioChunk(audioData)
        if (transcribedText.isBlank()) return ""

        this.currentTurn?.userQuery = transcribedText
        DashboardManager.sendChat(isUser = true, text = transcribedText, userImageUrl = this.currentTurn?.userImageUrl)
        logTurnState("User Text Appended", this.currentTurn)

        pipelineContext.sendSttTranscriptCompleted(transcribedText)

        return transcribedText
    }


    private suspend fun runAgenticLoop(initialContext: String, pipelineContext: PipelineContext) {
        var currentContext = initialContext
        var isPipelineActive = true
        var loopBreaker = 0

        while (isPipelineActive && loopBreaker < 2) {
            if (this.currentTurn == null) {
                Log.e( ProxySettingsRepository.TAG, "Current Turn is null. Can't continue agentic loop")
                return
            }
            loopBreaker++
            if (isResponseCancelled) return

            val llmMessageObj = LlmClient.fetchLlmResponse(currentContext, this.currentTurn!!) ?: return

            // LLM has safely consumed the context
            if (this.currentTurn?.userImageUrl.isNullOrBlank()) {
                isHoldingToolResult = false
            }

            // this.currentTurn?.userImageUrl = null

            val (updatedContext, continueLoop) = processLlmMessage(llmMessageObj, currentContext, pipelineContext)

            currentContext = updatedContext
            isPipelineActive = continueLoop
        }
        finalizePipelineTurn(pipelineContext)
    }

    private suspend fun processLlmMessage(llmMessageObj: JSONObject, currentContext: String, pipelineContext: PipelineContext): Pair<String, Boolean> {
        val rawContent = llmMessageObj.opt("content")
        val initialLlmText = if (rawContent == null || rawContent == JSONObject.NULL) "" else llmMessageObj.optString("content", "")

        return if (LlmClient.toolDetected(llmMessageObj)) {
            executeToolPhase(llmMessageObj, initialLlmText, currentContext, pipelineContext)
        } else {
            processLlmText(initialLlmText, pipelineContext, isThinkingText = false)
            Pair(currentContext, false) // false = deactivate pipeline loop
        }
    }

    private suspend fun executeToolPhase(llmMessageObj: JSONObject, initialLlmText: String, currentContext: String, pipelineContext: PipelineContext): Pair<String, Boolean> {
        val (toolName, toolArgs, cleanedText) = LlmClient.extractToolInformation(llmMessageObj, initialLlmText)

        val rawCallId = llmMessageObj.optJSONArray("tool_calls")?.optJSONObject(0)?.optString("id")
        val toolCallId = if (!rawCallId.isNullOrBlank()) rawCallId else "call_${System.currentTimeMillis()}"

        // Treat any text generated alongside the tool call as "thinking" text
        processLlmText(cleanedText, pipelineContext, isThinkingText = true)

        if (toolName == null) {
            pipelineContext.sendTranscriptDelta("Error")
            return Pair(currentContext, false)
        }

        if (cleanedText.isNotBlank()) {
            DashboardManager.sendChat(isUser = false, text = cleanedText)
        }

        val (updatedContext, executedLocally) = handleToolCall(
            toolName, toolArgs, toolCallId,
            currentContext, pipelineContext,
            this.currentTurn
        )

        if (executedLocally) {
            Log.i(ProxySettingsRepository.TAG, "Local tool executed. Looping back with content:  $updatedContext")
            return Pair(updatedContext, true) // true = keep looping to let LLM read the local result
        } else {
            isWaitingForAppToolResult = true
            queryWaitingForTool = currentTurn?.userQuery ?: "" // Cache query in case user speaks while waiting
            return Pair(currentContext, false) // false = break loop, wait for external app packet
        }
    }

    suspend fun handleToolCall(toolName: String, toolArgs: String?, toolCallId: String, currentContext: String, pipelineContext: PipelineContext, currentTurn: ChatTurn?): Pair<String, Boolean> {
        // create the new toolrecord which we can use in the
        currentTurn?.toolCalled = toolName+"_"+toolCallId
        logTurnState("Tool Called: $toolName", currentTurn)
        val arguments = JSONObject(toolArgs ?: "{}")
        val query = toolInterceptor.extractQueryOrInput(toolName, arguments)

        currentTurn?.addToolCall(toolCallId, toolName, query)

        return toolInterceptor.processToolCall(toolArgs, toolCallId, currentContext, currentTurn, pipelineContext)
    }

    private suspend fun processLlmText(llmText: String, pipelineContext: PipelineContext, isThinkingText: Boolean = false) {
        if (llmText.isNotBlank()) {
            pipelineContext.sendTranscriptDelta(llmText)
            AraWisperManager.speechManager.processTextChunk(llmText) { pcmBytes ->

                this.currentTurn?.audioBuffer?.write(pcmBytes)
                val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)

                serverScope.launch {
                    pipelineContext.sendAudioDelta(base64Audio)
                }
            }
            if (!isThinkingText) {
                this.currentTurn?.appendLlmText(llmText)
            }
        }
    }

    private suspend fun finalizePipelineTurn(pipelineContext: PipelineContext) {
        // Flush speech engine and wait until all TTS/Qi audio processing is complete
        AraWisperManager.speechManager.flush() { pcmBytes ->
            currentTurn?.audioBuffer?.write(pcmBytes)
            val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
            serverScope.launch {
                pipelineContext.sendAudioDelta(base64Audio)
            }
        }

        // Dispatch turn completion signals to client
        pipelineContext.sendAudioDone()
        pipelineContext.sendDone()

        // Persist history and dashboard data if not awaiting an app tool callback
        if (!isWaitingForAppToolResult) {
            persistTurnData()
            logTurnState("Finished", currentTurn)
            isPipelineRunning = false
        }


    }

    private suspend fun persistTurnData() {
        val turn = currentTurn ?: return

        Log.i(
            ProxySettingsRepository.TAG,
            "READY FOR DB: ${turn.turnId} | Q: ${turn.userQuery} | A: ${turn.llmResponse} | Tool: ${turn.toolCalled} | ToolBuffer: ${turn.toolBufferResponse}"
        )

        DashboardManager.sendChat(isUser = false, text = turn.llmResponse)

        if (!turn.isSystemEvent) {
            DataBaseClient.updateHistory(turn)
        } else {
            Log.i(
                ProxySettingsRepository.TAG,
                "System event turn completed. Bypassing DB save to protect user knowledge."
            )
        }
    }

    fun processAudio(audioBytes: ByteArray) {
        val finalBase64Speech = audioProcessor.processChunk(audioBytes)
        if (finalBase64Speech != null) {
            isPipelineRunning = true
            val sharedItemId = "item_${System.currentTimeMillis()}"

            isHoldingToolResult = false

            serverScope.launch {
                executePipeline(audioData = finalBase64Speech, initialText = "", sharedItemId)
            }
        }
    }

    fun updateIsResponseCancelled(cancelled: Boolean) {
        this.isResponseCancelled = cancelled
        if (cancelled) {
            Log.i(ProxySettingsRepository.TAG, "Interruption received! Cancelling proxy response.")
            AraWisperManager.speechManager.stop()
        }
    }

    private suspend fun applyTruncatedTextToCurrentTurn(rawTruncatedText: String) {
        currentTurn?.let {
            val originalText = it.llmResponse
            val fixedTruncatedText = SttClient.fixTranscribedTruncatedText(originalText, rawTruncatedText)

            it.llmResponse = "$fixedTruncatedText-"

            Log.i(ProxySettingsRepository.TAG, "Truncation applied!\nOriginal: $originalText\nTruncated: ${it.llmResponse}")
            logTurnState("Truncated via STT transcription", it)
            DataBaseClient.updateHistory(it)
        }
    }
}


