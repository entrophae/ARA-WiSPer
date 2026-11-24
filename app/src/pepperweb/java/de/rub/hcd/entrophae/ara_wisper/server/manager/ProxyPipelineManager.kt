package de.rub.hcd.entrophae.ara_wisper.server.manager

import android.util.Base64
import android.util.Base64.decode
import android.util.Log
import ch.fhnw.pepper_realtime.manager.TurnManager
import de.rub.hcd.entrophae.ara_wisper.interceptor.ToolInterceptor
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.PipelineContext
import de.rub.hcd.entrophae.ara_wisper.server.data.createPipelineContext
import de.rub.hcd.entrophae.ara_wisper.server.data.logTurnState
import de.rub.hcd.entrophae.ara_wisper.server.network.DataBaseClient
import de.rub.hcd.entrophae.ara_wisper.server.network.LlmClient
import de.rub.hcd.entrophae.ara_wisper.server.network.SttClient
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import de.rub.hcd.entrophae.ara_wisper.server.service.AudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ProxyPipelineManager(
    private val serverScope: CoroutineScope,
    private val sendToApp: suspend (String) -> Unit
) {
    val toolInterceptor = ToolInterceptor()
    val audioProcessor = AudioProcessor()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val audioSendDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val pendingAppToolCallIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var pendingToolTurnId: String? = null

    val currentUserId: String
        get() = ProxySettingsRepository.activeUserId

    var currentTurn: ChatTurn? = null
    @Volatile
    var isResponseCancelled = false

    @Volatile
    var isWaitingForAppToolResult = false
    @Volatile
    var isHoldingToolResult = false // Protects tool context from late tool system messages

    @Volatile
    var isPipelineRunning = false

    var latestUserText = "" // Lifetime until used by LLM

    private val isTurnBusy: Boolean
        get() = isPipelineRunning || pendingAppToolCallIds.isNotEmpty() || isWaitingForAppToolResult

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
        if (shouldDropIncomingAudio()) {
            // Ensure no leftover chunk stays buffered in VAD
            audioProcessor.reset()
            return
        }

        val audioData = json.optString(ProxySettingsRepository.Proxy.Keys.AUDIO)
        if (audioData.isBlank()) return
        try {
            val rawBytes = decode(audioData, Base64.NO_WRAP)
            processAudio(rawBytes)
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

    fun handleRespCreate(rawJson: String){
        DashboardManager.sendLog(rawJson, isFromRobot = true)
        this.isResponseCancelled = false
        updateIsResponseCancelled(false)

        val textToProcess = latestUserText
        if (textToProcess.isNotEmpty()) {
            val isToolResume = isHoldingToolResult
            val itemId = this.currentTurn?.turnId ?: ""

            if (!isToolResume || this.currentTurn == null) {
                this.currentTurn = ChatTurn.resolve(currentTurn, currentUserId, itemId, textToProcess).apply {
                    resetToolState()
                }
            }

            latestUserText = ""
            audioProcessor.reset()
            this.currentTurn?.audioBuffer?.reset()

            // Lock turn state synchronously before launching coroutine to avoid audio race window
            markTurnThinking()

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

        // Ignore stale tool outputs from canceled or superseded turns
        if (isResponseCancelled || (pendingToolTurnId != null && pendingToolTurnId != currentTurn?.turnId)) {
            Log.w(ProxySettingsRepository.TAG, "Ignoring stale tool output for call_id=$toolCallId")
            pendingAppToolCallIds.remove(toolCallId)
            isWaitingForAppToolResult = pendingAppToolCallIds.isNotEmpty()
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

        val turn = currentTurn
        val completedToolName = turn?.toolsUsed?.find { it.callId == toolCallId }?.name ?: "tool"
        turn?.addToolAnswer(toolCallId, toolAnswer)
        turn?.completedToolResults?.add(completedToolName to toolAnswer)
        pendingAppToolCallIds.remove(toolCallId)

        // Wait until all parallel app tools in this turn have reported back
        if (pendingAppToolCallIds.isNotEmpty()) {
            return
        }

        latestUserText = toolInterceptor.formatToolOutputPrompt(
            turn = turn,
            toolAnswer = toolAnswer,
            completedToolName = completedToolName
        )

        turn?.queryWaitingForTool = ""
        isWaitingForAppToolResult = false
        isHoldingToolResult = true // Protect this context until the LLM reads it

        // Fallback: if the app tool is fire-and-forget and never sends response.create,
        // persist the turn and release isHoldingToolResult after a short grace window
        val expectedTurnId = turn?.turnId
        serverScope.launch {
            kotlinx.coroutines.delay(1500L)
            if (isHoldingToolResult && !isPipelineRunning && currentTurn?.turnId == expectedTurnId) {
                Log.i(ProxySettingsRepository.TAG, "Fire-and-forget tool completed without response.create; finalizing turn.")
                isHoldingToolResult = false
                latestUserText = ""
                persistTurnData()
                markTurnListening(stopSpeech = false, clearCooldown = false)
            }
        }
    }

    private fun processImageItem(imageUrl: String) {
        Log.i(TAG, "Proxy intercepted image payload! Caching for VLM.")

        this.currentTurn?.let { turn ->
            turn.userImageUrl = imageUrl
            // Instantly push this image to the dashboard live log for this item ID
            try {
                DashboardManager.updateChat(turnId = turn.turnId, isUser = true, userImageUrl = imageUrl)
            } catch (t: Throwable) {
                Log.e(TAG, "DashboardManager.updateChat failed for image payload", t)
            }
        }

        // Only restart dashboard stream if we are not actively waiting on a vision tool result
        if (!isWaitingForAppToolResult) {
            try {
                AraWisperManager.dashboardVideoManager.restartCameraStream()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to restart dashboard camera stream", t)
            }
        }
    }

    private fun processManualCommandItem(cleanText: String, nativeItemId: String) {
        isHoldingToolResult = false
        isWaitingForAppToolResult = false
        this.currentTurn = ChatTurn.resolve(currentTurn, currentUserId, nativeItemId, cleanText).apply {
            resetToolState()
        }

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
        Log.i(TAG, "Proxy intercepted System Event: $cleanText")

        // Drop late system noise to protect active tool context
        if (isHoldingToolResult) {
            Log.i(TAG, "Dropped system noise to protect active tool context.")
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
            this.currentTurn = ChatTurn.resolve(this.currentTurn, currentUserId, nativeItemId, cleanText, isSystem = true).apply {
                resetToolState()
            }
        } else {
            Log.i(TAG, "Appended system event to ongoing turn. Preserving DB save state.")
        }

        DashboardManager.sendChat(
            isUser = true,
            text = "⚙️ $cleanText",
            userImageUrl = this.currentTurn?.userImageUrl
        )
    }


    suspend fun executePipeline(audioData: String, initialText: String, itemId: String) {
        markTurnThinking()
        audioProcessor.reset()

        try {
            val currentResponseId = "proxy_resp_" + java.util.UUID.randomUUID().toString().substring(0, 8)
            val pipelineContext = createPipelineContext(currentResponseId, sendToApp, audioSendDispatcher)

            // Prepare Text
            val textToProcess = preparePipelineInput(audioData, initialText, itemId, pipelineContext)
            if (textToProcess.isBlank()) return

            // Notify App
            pipelineContext.sendThinkingPacket()
            pipelineContext.sendOutputItemAdded(itemId + "_assistant")

            // Start AI Loop
            runAgenticLoop(textToProcess, pipelineContext)
        } finally {
            isPipelineRunning = false
            audioProcessor.reset()
            if (!isWaitingForAppToolResult) {
                markTurnListening(stopSpeech = false, clearCooldown = false)
            } else {
                // Keep turn active while waiting for external app/robot tool completion
                AraWisperManager.speechManager.isTurnActive = true
            }
        }
    }

    private suspend fun preparePipelineInput(audioData: String, initialText: String, itemId: String, pipelineContext: PipelineContext): String {
        // If we already have text, just return it
        if (audioData.isEmpty() || initialText.isNotBlank()) return initialText

        // Otherwise, process audio transcription with an empty turn being initiated first
        this.currentTurn = ChatTurn.resolve(currentTurn, currentUserId, itemId, initialText).apply {
            resetToolState()
        }
        sendToApp(WebSocketPayloads.userItemPlaceholder(itemId))

        val transcribedText = withContext(Dispatchers.IO) {
            SttClient.sendAudioChunk(audioData)
        }
        if (transcribedText.isBlank()) return ""

        this.currentTurn?.userQuery = transcribedText
        DashboardManager.sendChat(isUser = true, text = transcribedText, userImageUrl = this.currentTurn?.userImageUrl)
        logTurnState("User Text Appended", this.currentTurn)

        pipelineContext.sendSttTranscriptCompleted(transcribedText)

        return transcribedText
    }


    private suspend fun runAgenticLoop(initialContext: String, pipelineContext: PipelineContext) {
        var currentContext = initialContext
        var loopBreaker = 0

        while (loopBreaker < MAX_APP_TOOL_ROUNDS_PER_TURN) {
            if (this.currentTurn == null) {
                Log.e(TAG, "Current Turn is null. Can't continue agentic loop")
                return
            }
            loopBreaker++
            if (isResponseCancelled) return

            val turn = this.currentTurn!!
            val llmMessageObj = withContext(Dispatchers.IO) {
                LlmClient.fetchLlmResponse(currentContext, turn)
            } ?: return

            // LLM has safely consumed the context
            isHoldingToolResult = false

            val (updatedContext, continueLoop) = processLlmMessage(llmMessageObj, currentContext, pipelineContext)

            currentContext = updatedContext
            if (!continueLoop) break
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

    private fun isRepeatAppToolBlocked(toolName: String, turn: ChatTurn?): Boolean {
        return (toolName in SINGLE_SHOT_TOOLS && turn?.completedAppTools?.contains(toolName) == true) ||
                (turn?.appToolRounds ?: 0) >= MAX_APP_TOOL_ROUNDS_PER_TURN
    }

    private suspend fun executeToolPhase(llmMessageObj: JSONObject, initialLlmText: String, currentContext: String, pipelineContext: PipelineContext): Pair<String, Boolean> {
        val (toolCalls, cleanedText) = LlmClient.extractToolCalls(llmMessageObj, initialLlmText)
        val turn = this.currentTurn

        if (toolCalls.isEmpty()) {
            pipelineContext.sendTranscriptDelta("Error")
            return Pair(currentContext, false)
        }

        // Only speak thinking/filler text if at least one tool in this batch will actually run
        val hasExecutableTool = toolCalls.any { !isRepeatAppToolBlocked(it.name, turn) }
        if (hasExecutableTool && cleanedText.isNotBlank()) {
            processLlmText(cleanedText, pipelineContext, isThinkingText = true)
            turn?.toolSynchronizer?.recordSpokenText(cleanedText)
            DashboardManager.sendChat(isUser = false, text = cleanedText)
            flushSpeechAudio(pipelineContext)
        }

        var rollingContext = currentContext
        var allExecutedLocally = true
        val seenSignaturesInBatch = mutableSetOf<String>()

        for (call in toolCalls) {
            // Deduplicate identical calls in the same tool_calls batch
            val signature = if (call.name in SINGLE_SHOT_TOOLS) call.name else "${call.name}:${call.arguments}"
            if (!seenSignaturesInBatch.add(signature)) {
                Log.w(TAG, "Skipping duplicate parallel tool call: $signature")
                continue
            }

            if (isRepeatAppToolBlocked(call.name, turn)) {
                Log.w(ProxySettingsRepository.TAG, "Blocking repeat app tool '${call.name}' in same turn; forcing direct answer.")
                rollingContext = """
                    $rollingContext

                    ## System-Info (${call.name}):
                    Das Tool '${call.name}' wurde in diesem Zug bereits ausgeführt und das Ergebnis liegt dir bereits vor. Rufe '${call.name}' NICHT erneut auf. Nutze die vorhandenen Informationen (und rufe bei Bedarf nur noch ein anderes angefordertes Tool auf, falls noch ein Teil der Anfrage offen ist).
                """.trimIndent()
                continue
            }

            val (nextContext, executedLocally) = handleToolCall(
                call.name, call.arguments, call.id, rollingContext, pipelineContext, turn
            )
            rollingContext = nextContext

            if (!executedLocally) {
                allExecutedLocally = false
                turn?.completedAppTools?.add(call.name)
                pendingAppToolCallIds.add(call.id)
                pendingToolTurnId = turn?.turnId
            }
        }

        return if (allExecutedLocally) {
            Log.i(TAG, "Local tool executed. Looping back with content: $rollingContext")
            Pair(rollingContext, true) // true = keep looping to let LLM read the local result
        } else {
            turn?.let {
                it.appToolRounds++
                it.queryWaitingForTool = it.userQuery
            }
            isWaitingForAppToolResult = true
            Pair(rollingContext, false) // false = break loop, wait for external app packet
        }

    }

    suspend fun handleToolCall(toolName: String, toolArgs: String?, toolCallId: String, currentContext: String, pipelineContext: PipelineContext, currentTurn: ChatTurn?): Pair<String, Boolean> {
        // create the new toolrecord which we can use in the
        currentTurn?.toolCalled = toolName+"_"+toolCallId
        logTurnState("Tool Called: $toolName", currentTurn)
        val arguments = JSONObject(toolArgs ?: "{}")
        val query = toolInterceptor.extractQueryOrInput(toolName, arguments)

        currentTurn?.addToolCall(toolCallId, toolName, query)

        currentTurn?.toolSynchronizer?.awaitBeforeToolExecution(toolName) { isResponseCancelled }

        // Run tool execution on Dispatchers.IO so blocking RAG/HTTP calls never stall audio streaming
        return withContext(Dispatchers.IO) {
            toolInterceptor.processToolCall(toolArgs, toolCallId, currentContext, currentTurn, pipelineContext)
        }
    }

    private suspend fun flushSpeechAudio(pipelineContext: PipelineContext) {
        AraWisperManager.speechManager.flush { pcmBytes ->
            currentTurn?.audioBuffer?.write(pcmBytes)
            val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
            pipelineContext.enqueueAudioDelta(serverScope, base64Audio)
        }
        pipelineContext.awaitAudioFlushed()
    }

    private suspend fun processLlmText(llmText: String, pipelineContext: PipelineContext, isThinkingText: Boolean = false) {
        if (llmText.isNotBlank()) {
            pipelineContext.sendTranscriptDelta(llmText)
            AraWisperManager.speechManager.processTextChunk(llmText) { pcmBytes ->
                this.currentTurn?.audioBuffer?.write(pcmBytes)
                val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
                pipelineContext.enqueueAudioDelta(serverScope, base64Audio)
            }
            if (!isThinkingText) {
                this.currentTurn?.appendLlmText(llmText)
            }
        }
    }

    private suspend fun finalizePipelineTurn(pipelineContext: PipelineContext) {
        // Flush speech engine and wait until all TTS/Qi audio processing is complete
        flushSpeechAudio(pipelineContext)

        // Dispatch turn completion signals to client
        pipelineContext.sendAudioDone()

        // Persist history and dashboard data if not awaiting an app tool callback
        if (!isWaitingForAppToolResult) {
            pipelineContext.sendDone()
            persistTurnData()
            logTurnState("Finished", currentTurn)
        }
    }

    private suspend fun persistTurnData() {
        val turn = currentTurn ?: return

        Log.i(
            TAG,
            "READY FOR DB: ${turn.turnId} | Q: ${turn.userQuery} | A: ${turn.llmResponse} | Tool: ${turn.toolCalled} | ToolBuffer: ${turn.toolBufferResponse}"
        )

        DashboardManager.sendChat(isUser = false, text = turn.llmResponse)

        if (!turn.isSystemEvent) {
            DataBaseClient.updateHistory(turn)
        } else {
            Log.i( TAG, "System event turn completed. Bypassing DB save to protect user knowledge." )
        }
    }

    // Incoming Audio should be dropped when muted, speaking or thinking (if self-muting didn't work somehow)
    private fun shouldDropIncomingAudio(): Boolean {
        val adapter = AraWisperManager.chatActivityAdapter
        val turnManager = adapter?.turnManager
        val turnState = turnManager?.state
        val isRobotBusyState = turnState == TurnManager.State.THINKING || turnState == TurnManager.State.SPEAKING

        // Enforce THINKING lock if main's ChatRealtimeHandler drifted to LISTENING while a turn/tool is active
        if (isTurnBusy && turnState == TurnManager.State.LISTENING && !adapter.viewModel.isAudioPlaying.value
        ) {
            turnManager.setState(TurnManager.State.THINKING)
        }
        return adapter?.audioInputController?.isMuted == true ||
                AraWisperManager.speechManager.isSpeakingOrCoolingDown() ||
                isTurnBusy ||
                isRobotBusyState
    }

    private fun markTurnThinking() {
        isPipelineRunning = true
        AraWisperManager.speechManager.isTurnActive = true
        AraWisperManager.chatActivityAdapter?.let { adapter ->
            adapter.viewModel.setResponseGenerating(true)
            if (adapter.turnManager?.state != TurnManager.State.SPEAKING) {
                adapter.turnManager?.setState(TurnManager.State.THINKING)
            }
        }
    }

    private fun markTurnListening(stopSpeech: Boolean, clearCooldown: Boolean) {
        isPipelineRunning = false
        AraWisperManager.speechManager.isTurnActive = false
        audioProcessor.reset()

        if (stopSpeech) {
            AraWisperManager.speechManager.stop(clearCooldown = clearCooldown)
        }

        AraWisperManager.chatActivityAdapter?.let { adapter ->
            adapter.viewModel.setResponseGenerating(false)
            if (!AraWisperManager.speechManager.isSpeakingOrCoolingDown()) {
                adapter.viewModel.setAudioPlaying(false)
                adapter.turnManager?.setState(TurnManager.State.LISTENING)
            }
        }
    }

    fun processAudio(audioBytes: ByteArray) {
        val finalBase64Speech = audioProcessor.processChunk(audioBytes)
        if (finalBase64Speech != null) {
            val sharedItemId = "item_${System.currentTimeMillis()}"

            currentTurn?.resetToolState()
            isHoldingToolResult = false
            isResponseCancelled = false

            // Lock turn state synchronously before launching coroutine
            markTurnThinking()

            serverScope.launch {
                executePipeline(audioData = finalBase64Speech, initialText = "", sharedItemId)
            }
        }
    }

    fun updateIsResponseCancelled(cancelled: Boolean) {
        this.isResponseCancelled = cancelled
        if (cancelled) {
            Log.i(TAG, "Interruption received! Cancelling proxy response.")
            currentTurn?.resetToolState()
            pendingAppToolCallIds.clear()
            pendingToolTurnId = null
            isWaitingForAppToolResult = false
            isHoldingToolResult = false
            latestUserText = ""
            markTurnListening(stopSpeech = true, clearCooldown = true)
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

    companion object {
        private const val TAG = "ProxyPipelineManager"
        private const val MAX_APP_TOOL_ROUNDS_PER_TURN = 4
        private val SINGLE_SHOT_TOOLS = setOf("analyze_vision")
    }
}