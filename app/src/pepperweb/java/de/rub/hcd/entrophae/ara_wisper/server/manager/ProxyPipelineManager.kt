package de.rub.hcd.entrophae.ara_wisper.server.manager

import android.util.Base64
import android.util.Base64.decode
import android.util.Log
import de.rub.hcd.entrophae.ara_wisper.interceptor.ToolInterceptor
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.manager.ProxyStateBridge
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.PipelineContext
import de.rub.hcd.entrophae.ara_wisper.server.data.logTurnState
import de.rub.hcd.entrophae.ara_wisper.server.network.DataBaseClient
import de.rub.hcd.entrophae.ara_wisper.server.network.LlmClient
import de.rub.hcd.entrophae.ara_wisper.server.network.SttClient
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import de.rub.hcd.entrophae.ara_wisper.server.service.AudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ProxyPipelineManager(
    private val serverScope: CoroutineScope
) {
    private enum class TurnPhase {
        IDLE,
        RUNNING,
        WAITING_FOR_TOOL,
        HOLDING_TOOL_RESULT
    }

    private val toolInterceptor = ToolInterceptor()
    val audioProcessor = AudioProcessor()

    @Volatile
    var currentTurn: ChatTurn? = null

    @Volatile
    private var phase: TurnPhase = TurnPhase.IDLE

    @Volatile
    private var activePipelineJob: Job? = null

    private val isTurnBusy: Boolean
        get() = phase == TurnPhase.RUNNING ||
                phase == TurnPhase.WAITING_FOR_TOOL ||
                phase == TurnPhase.HOLDING_TOOL_RESULT ||
                currentTurn?.hasPendingAppTools == true

    fun handleSessionUpdate(json: JSONObject, rawJson: String){
        DashboardManager.sendLog(rawJson, true)

        val sessionObj = json.optJSONObject("session")

        val instructions = sessionObj?.optString("instructions", "")
        if (!instructions.isNullOrEmpty()) {
            ProxySettingsRepository.customSystemPrompt = instructions
            Log.i(TAG, "Proxy intercepted new system prompt!")
        }

        val tools = sessionObj?.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            toolInterceptor.injectTools(tools)
            ProxySettingsRepository.currentTools = tools
            val toolNames = mutableListOf<String>()
            for (i in 0 until tools.length()) {
                toolNames.add(tools.getJSONObject(i).optString("name", "unknown"))
            }
            Log.i(TAG, "Proxy loaded tools: $toolNames")
        }

        audioProcessor.reconfigure()
    }

    fun handleAudioAppend(json: JSONObject){
        if (ProxyStateBridge.shouldDropIncomingAudio(isTurnBusy, audioProcessor)) return

        val audioData = json.optString(ProxySettingsRepository.Proxy.Keys.AUDIO)
        if (audioData.isBlank()) return
        try {
            processAudio(audioData)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to decode audio base64", e)
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

    fun handleRespCreate(rawJson: String) {
        DashboardManager.sendLog(rawJson, isFromRobot = true)

        val turn = currentTurn ?: return
        val textToProcess = turn.consumePendingPrompt()
        if (textToProcess.isEmpty()) return

        if (phase != TurnPhase.HOLDING_TOOL_RESULT) {
            turn.resetToolState()
        }

        audioProcessor.reset()
        turn.audioBuffer.reset()
        launchPipeline(turn = turn, initialPrompt = textToProcess)
    }

    fun handleItemTruncated(json: JSONObject) {
        val audioEndMs = json.optInt("audio_end_ms", 0)
        if (audioEndMs <= 0) return

        serverScope.launch {
            SttClient.handleAudioTruncation(currentTurn, audioEndMs, audioProcessor)
        }
    }

    private fun launchPipeline(turn: ChatTurn, audioData: String = "", initialPrompt: String = "") {
        activePipelineJob?.cancel()
        phase = TurnPhase.RUNNING
        ProxyStateBridge.markThinking()

        val responseId = "proxy_resp_" + java.util.UUID.randomUUID().toString().substring(0, 8)
        val turnBridge = ProxyStateBridge.startTurn(responseId = responseId, turnId = turn.turnId)
        val pipelineContext = PipelineContext(
            turn = turn,
            currentPrompt = initialPrompt,
            bridge = turnBridge
        )

        activePipelineJob = serverScope.launch {
            executePipeline(pipelineContext, audioData)
        }
    }

    private fun processToolOutputItem(itemObj: JSONObject) {
        val toolCallId = itemObj.optString("call_id", itemObj.optString("tool_call_id", ""))
        val turn = currentTurn

        if (turn == null || turn.isCancelled || !turn.pendingAppToolCallIds.contains(toolCallId)) {
            Log.w(ProxySettingsRepository.TAG, "Ignoring stale tool output for call_id=$toolCallId")
            return
        }

        val toolOutput = itemObj.optString("output", "")
        val toolAnswer = if (toolOutput.isNotBlank()) {
            try {
                val parsed = JSONObject(toolOutput)
                if (parsed.has("answer")) parsed.getString("answer") else toolOutput
            } catch (_: Exception) {
                toolOutput
            }
        } else ""

        Log.i(TAG, "Tool-Ergebnis abgefangen ($toolCallId): $toolAnswer \n\n")

        val completedToolName = turn.completeAppTool(toolCallId, toolAnswer)
        if (turn.hasPendingAppTools) return

        turn.pendingPrompt = toolInterceptor.formatToolOutputPrompt(
            turn = turn,
            toolAnswer = toolAnswer,
            completedToolName = completedToolName
        )

        turn.queryWaitingForTool = ""
        phase = TurnPhase.HOLDING_TOOL_RESULT

        // Fallback if a fire-and-forget app tool never triggers response.create
        serverScope.launch {
            delay(1500L)
            if (phase == TurnPhase.HOLDING_TOOL_RESULT && currentTurn === turn && !turn.isCancelled) {
                Log.i(ProxySettingsRepository.TAG, "Fire-and-forget tool completed without response.create; finalizing turn.")
                phase = TurnPhase.IDLE
                turn.pendingPrompt = ""
                turn.persist()
                ProxyStateBridge.markListening()
            }
        }
    }

    private fun processImageItem(imageUrl: String) {
        Log.i(TAG, "Proxy intercepted image payload! Caching for VLM.")

        currentTurn?.let { turn ->
            turn.userImageUrl = imageUrl
            // Instantly push this image to the dashboard live log for this item ID
            try {
                DashboardManager.updateChat(turnId = turn.turnId, isUser = true, userImageUrl = imageUrl)
            } catch (t: Throwable) {
                Log.e(TAG, "DashboardManager.updateChat failed for image payload", t)
            }
        }

        // Only restart dashboard stream if we are not actively waiting on a vision tool result
        if (phase != TurnPhase.WAITING_FOR_TOOL) {
            try {
                AraWisperManager.dashboardVideoManager.restartCameraStream()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to restart dashboard camera stream", t)
            }
        }
    }

    private fun processManualCommandItem(cleanText: String, nativeItemId: String) {
        phase = TurnPhase.IDLE
        ChatTurn.resolve(currentTurn, nativeItemId, cleanText).also { turn ->
            turn.resetToolState()
            turn.pendingPrompt = cleanText
            currentTurn = turn
            if (cleanText.isNotBlank()) {
                DashboardManager.sendChat(isUser = true, text = cleanText, userImageUrl = turn.userImageUrl)
            }
        }
    }

    private fun processSystemEventItem(cleanText: String, nativeItemId: String) {
        Log.i(TAG, "Proxy intercepted System Event: $cleanText")

        if (phase == TurnPhase.HOLDING_TOOL_RESULT) {
            Log.i(TAG, "Dropped system noise to protect active tool context.")
            return
        }
        if (phase == TurnPhase.WAITING_FOR_TOOL) return

        if (currentTurn == null || currentTurn?.llmResponse?.isNotEmpty() == true) {
            ChatTurn.resolve(currentTurn, nativeItemId, cleanText, isSystem = true).also { turn ->
                turn.resetToolState()
                currentTurn = turn
            }
        } else {
            Log.i(TAG, "Appended system event to ongoing turn. Preserving DB save state.")
        }

        currentTurn?.pendingPrompt = """
            [INTERNES SYSTEM-UPDATE]:
            $cleanText
            
            Beziehe diese Information in deine nächste Antwort ein.
        """.trimIndent()

        DashboardManager.sendChat(
            isUser = true,
            text = "⚙️ $cleanText",
            userImageUrl = currentTurn?.userImageUrl
        )
    }

    private suspend fun executePipeline(pipelineContext: PipelineContext, audioData: String) {
        audioProcessor.reset()

        try {
            if (!preparePipelineInput(pipelineContext, audioData)) return

            pipelineContext.bridge.onThinkingStarted()
            runAgenticLoop(pipelineContext)
        } finally {
            if (currentTurn === pipelineContext.turn) {
                audioProcessor.reset()
                if (phase == TurnPhase.WAITING_FOR_TOOL) {
                    ProxyStateBridge.keepTurnActiveForExternalTool()
                } else {
                    if (phase == TurnPhase.RUNNING) phase = TurnPhase.IDLE
                    ProxyStateBridge.markListening()
                }
            }
        }
    }

    private suspend fun preparePipelineInput(pipelineContext: PipelineContext, audioData: String): Boolean {
        if (audioData.isEmpty() || pipelineContext.currentPrompt.isNotBlank()) {
            return pipelineContext.currentPrompt.isNotBlank()
        }

        pipelineContext.bridge.onUserSpeechCommitted()

        val transcribedText = withContext(Dispatchers.IO) {
            SttClient.sendAudioChunk(audioData)
        }
        if (transcribedText.isBlank()) return false

        pipelineContext.turn.userQuery = transcribedText
        pipelineContext.currentPrompt = transcribedText
        DashboardManager.sendChat(isUser = true, text = transcribedText, userImageUrl = pipelineContext.turn.userImageUrl)
        logTurnState("User Text Appended", pipelineContext.turn)
        pipelineContext.bridge.onUserTranscriptReady(transcribedText)

        return true
    }

    private suspend fun runAgenticLoop(pipelineContext: PipelineContext) {
        var loopBreaker = 0

        while (loopBreaker < MAX_APP_TOOL_ROUNDS_PER_TURN && !pipelineContext.turn.isCancelled) {
            loopBreaker++

            val llmMessageObj = withContext(Dispatchers.IO) {
                LlmClient.fetchLlmResponse(pipelineContext.currentPrompt, pipelineContext.turn)
            } ?: return

            val continueLoop = processLlmMessage(llmMessageObj, pipelineContext)
            if (!continueLoop) break
        }
        finalizePipelineTurn(pipelineContext)
    }

    private suspend fun processLlmMessage(llmMessageObj: JSONObject, pipelineContext: PipelineContext): Boolean {
        val rawContent = llmMessageObj.opt("content")
        val initialLlmText = if (rawContent == null || rawContent == JSONObject.NULL) "" else llmMessageObj.optString("content", "")

        return if (LlmClient.toolDetected(llmMessageObj)) {
            executeToolPhase(llmMessageObj, initialLlmText, pipelineContext)
        } else {
            pipelineContext.bridge.onAssistantOutputChunk(
                text = initialLlmText,
                isThinkingText = false,
                onPcmGenerated = { pcm -> pipelineContext.turn.audioBuffer.write(pcm) },
                onAcceptedText = { accepted -> pipelineContext.turn.appendLlmText(accepted) }
            )
            false
        }
    }

    private fun isRepeatAppToolBlocked(toolName: String, turn: ChatTurn): Boolean {
        return (toolName in SINGLE_SHOT_TOOLS && turn.completedAppTools.contains(toolName)) ||
                turn.appToolRounds >= MAX_APP_TOOL_ROUNDS_PER_TURN
    }

    private suspend fun executeToolPhase(llmMessageObj: JSONObject, initialLlmText: String, pipelineContext: PipelineContext): Boolean {
        val (toolCalls, cleanedText) = LlmClient.extractToolCalls(llmMessageObj, initialLlmText)

        if (toolCalls.isEmpty()) {
            pipelineContext.bridge.onAssistantOutputChunk(text = "Error", isThinkingText = true)
            return false
        }

        val hasExecutableTool = toolCalls.any { !isRepeatAppToolBlocked(it.name, pipelineContext.turn) }
        if (hasExecutableTool && cleanedText.isNotBlank()) {
            speakThinkingText(cleanedText, pipelineContext)
        }

        var allExecutedLocally = true
        val seenSignaturesInBatch = mutableSetOf<String>()

        val validCalls = buildList {
            for (call in toolCalls) {
                val signature = if (call.name in SINGLE_SHOT_TOOLS) call.name else "${call.name}:${call.arguments}"
                if (!seenSignaturesInBatch.add(signature)) {
                    Log.w(TAG, "Skipping duplicate parallel tool call: $signature")
                    continue
                }

                if (isRepeatAppToolBlocked(call.name, pipelineContext.turn)) {
                    Log.w(ProxySettingsRepository.TAG, "Blocking repeat app tool '${call.name}' in same turn; forcing direct answer.")
                    pipelineContext.currentPrompt = """
                        ${pipelineContext.currentPrompt}

                        ## System-Info (${call.name}):
                        Das Tool '${call.name}' wurde in diesem Zug bereits ausgeführt und das Ergebnis liegt dir bereits vor. Rufe '${call.name}' NICHT erneut auf. Nutze die vorhandenen Informationen (und rufe bei Bedarf nur noch ein anderes angefordertes Tool auf, falls noch ein Teil der Anfrage offen ist).
                    """.trimIndent()
                    continue
                }

                if (ProxySettingsRepository.isStandardTool(call.name)) {
                    pipelineContext.turn.registerPendingAppTool(call.name, call.id)
                }
                add(call)
            }
        }

        for (call in validCalls) {
            val executedLocally = handleToolCall(call.name, call.arguments, call.id, pipelineContext)
            if (!executedLocally) {
                allExecutedLocally = false
            }
        }

        return if (allExecutedLocally) {
            Log.i(TAG, "Local tool executed. Looping back with content: ${pipelineContext.currentPrompt}")
            true
        } else {
            pipelineContext.turn.appToolRounds++
            pipelineContext.turn.queryWaitingForTool = pipelineContext.turn.userQuery
            phase = TurnPhase.WAITING_FOR_TOOL
            false
        }
    }

    private suspend fun speakThinkingText(text: String, pipelineContext: PipelineContext) {
        pipelineContext.bridge.onAssistantOutputChunk(
            text = text,
            isThinkingText = true,
            onPcmGenerated = { pcm -> pipelineContext.turn.audioBuffer.write(pcm) }
        )
        pipelineContext.turn.toolSynchronizer.recordSpokenText(text)
        DashboardManager.sendChat(isUser = false, text = text)
        pipelineContext.bridge.flushSpeech { pcm ->
            pipelineContext.turn.audioBuffer.write(pcm)
        }
    }

    suspend fun handleToolCall(toolName: String, toolArgs: String?, toolCallId: String, pipelineContext: PipelineContext): Boolean {
        pipelineContext.turn.toolCalled = "${toolName}_$toolCallId"
        logTurnState("Tool Called: $toolName", pipelineContext.turn)

        val arguments = JSONObject(toolArgs ?: "{}")
        val query = toolInterceptor.extractQueryOrInput(toolName, arguments)
        pipelineContext.turn.addToolCall(toolCallId, toolName, query)

        pipelineContext.turn.toolSynchronizer.awaitBeforeToolExecution(toolName) { pipelineContext.turn.isCancelled }

        // Run tool execution on Dispatchers.IO so blocking RAG/HTTP calls never stall audio streaming
        return withContext(Dispatchers.IO) {
            toolInterceptor.processToolCall(toolArgs, toolCallId, pipelineContext)
        }
    }

    private suspend fun finalizePipelineTurn(pipelineContext: PipelineContext) {
        val waitingForTool = (phase == TurnPhase.WAITING_FOR_TOOL)
        val cancelled = pipelineContext.turn.isCancelled

        pipelineContext.bridge.onTurnCompleted(
            isWaitingForTool = waitingForTool,
            isCancelled = cancelled,
            onPcmGenerated = { pcm -> pipelineContext.turn.audioBuffer.write(pcm) }
        )

        if (!waitingForTool && !cancelled) {
            pipelineContext.turn.persist()
            logTurnState("Finished", pipelineContext.turn)
        }
    }

    fun processAudio(audioData: String) {
        val audioBytes = decode(audioData, Base64.NO_WRAP)
        val finalBase64Speech = audioProcessor.processChunk(audioBytes) ?: return

        ChatTurn.resolve(currentTurn).also { turn ->
            currentTurn = turn
            launchPipeline(turn = turn, audioData = finalBase64Speech)
        }

    }

    fun updateIsResponseCancelled(cancelled: Boolean) {
        if (!cancelled) return

        Log.i(TAG, "Interruption received! Cancelling proxy response.")
        currentTurn?.cancel()
        activePipelineJob?.cancel()
        activePipelineJob = null
        phase = TurnPhase.IDLE
        audioProcessor.reset()
        ProxyStateBridge.markListening(stopSpeech = true, clearCooldown = true)
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

    companion object {
        private const val TAG = "ProxyPipelineManager"
        private const val MAX_APP_TOOL_ROUNDS_PER_TURN = 4
        private val SINGLE_SHOT_TOOLS = setOf("analyze_vision")
    }
}