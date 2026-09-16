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
import de.rub.hcd.entrophae.ara_wisper.server.network.DataBaseClient
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.createTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.logTurnState
import de.rub.hcd.entrophae.ara_wisper.server.network.LlmClient
import de.rub.hcd.entrophae.ara_wisper.server.network.SttClient
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import de.rub.hcd.entrophae.ara_wisper.server.service.AudioProcessor
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
    val audioProcessor = AudioProcessor()

    var apiSupportsToolsTask: Deferred<Boolean>? = null

    var currentSystemPrompt: String = ProxySettingsRepository.customSystemPrompt.ifEmpty { ProxySettingsRepository.defaultSystemPrompt }
    var currentTools: JSONArray? = null
    var isResponseCancelled = false

    var latestItemId = "" // Prevents duplicate chat bubbles if currentTurn is dropped.
    var latestUserText = "" // Lifetime until used by LLM
    var lastKnownUserQuery = "" // Lifetime until new query enters. Reminds LLM the context of the tool-use
    var queryWaitingForTool = "" // Traps the context during tool execution

    val currentUserId: String
        get() = ProxySettingsRepository.activeUserId

    var currentTurn: ChatTurn? = null
    private val currentTurnAudioBuffer = java.io.ByteArrayOutputStream()
    var isWaitingForAppToolResult = false

    fun updateIsResponseCancelled(cancelled: Boolean) {
        Log.i(ProxySettingsRepository.TAG, "Interruption received! Cancelling proxy response.")
        this.isResponseCancelled = cancelled
        if (cancelled) {
            AraWisperManager.speechManager.stop()
        }
    }

    fun fixTranscribedTruncatedText(original: String, truncated: String): String {
        if (original.isBlank() || truncated.isBlank()) return original

        // Extract just the spoken words, ignoring all punctuation and markdown
        val wordRegex = Regex("[\\p{L}\\p{N}]+")
        val truncatedWordCount = wordRegex.findAll(truncated).count()
        val originalWords = wordRegex.findAll(original).toList()

        if (truncatedWordCount == 0 || originalWords.isEmpty()) return original

        // Take exactly the number of words STT heard, capped at the original length
        val wordsToKeep = minOf(truncatedWordCount, originalWords.size)

        // Find the index in the original string right after that final word
        val cutoffIndex = originalWords[wordsToKeep - 1].range.last + 1
        return original.substring(0, cutoffIndex)
    }

    fun handleItemTruncated(json: JSONObject) {
        val audioEndMs = json.optInt("audio_end_ms", 0)
        if (audioEndMs > 0) {
            serverScope.launch {
                // 16kHz PCM16 mono = 32 bytes per millisecond
                val byteCount = audioEndMs * 32
                val allBytes = currentTurnAudioBuffer.toByteArray()

                if (allBytes.isNotEmpty() && byteCount > 0) {
                    val sliceLength = minOf(byteCount, allBytes.size)
                    val slicedAudio = allBytes.copyOfRange(0, sliceLength)
                    val base64Audio = android.util.Base64.encodeToString(slicedAudio, android.util.Base64.NO_WRAP)

                    val rawTruncatedText = SttClient.sendAudioChunk(base64Audio)

                    currentTurn?.let {
                        if (rawTruncatedText.isNotBlank()) {
                            val originalText = it.llmResponse
                            val fixedTruncatedText = fixTranscribedTruncatedText(originalText, rawTruncatedText)
                            it.llmResponse = "$fixedTruncatedText-"
                            Log.i(ProxySettingsRepository.TAG, "Truncation applied!\nOriginal: $originalText\nTruncated: ${it.llmResponse}")
                            logTurnState("Truncated via STT transcription", it)
                            DataBaseClient.updateHistory(it)
                        }
                    }
                }
            }
        }
    }

    fun processAudio(audioBytes: ByteArray) {
        val finalBase64Speech = audioProcessor.processChunk(audioBytes)
        if (finalBase64Speech != null) {
            val sharedItemId = "item_${System.currentTimeMillis()}"
            serverScope.launch {
                executePipeline(audioData = finalBase64Speech, initialText = "", sharedItemId)
            }
        }
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
        
        audioProcessor.reconfigure()
    }

    fun handleAudioAppend(json: JSONObject){
        val audioData = json.optString(ProxySettingsRepository.Proxy.Keys.AUDIO)
        if (audioData.isBlank()) return
        try {
            val rawBytes = decode(audioData, android.util.Base64.NO_WRAP)
            processAudio(rawBytes)
        } catch (e: IllegalArgumentException) {
            Log.e(ProxySettingsRepository.TAG, "Failed to decode audio base64", e)
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
        AraWisperManager.speechManager.stop()

        val textToProcess = latestUserText
        if (textToProcess.isNotBlank()) {
            val itemId = this.currentTurn?.turnId ?: latestItemId
            this.currentTurn = createTurn(currentUserId, itemId, textToProcess, this.currentTurn)

            latestUserText = ""
            audioProcessor.reset()
            
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

            //TODO: check if first creting textToProcess and then creating Turn is not better. might break turn item or could be delayed?
            this.currentTurn = createTurn(currentUserId, itemId, initialText, this.currentTurn)

            sendToApp(WebSocketPayloads.userItemPlaceholder(itemId))

            textToProcess = SttClient.sendAudioChunk(audioData)
            if (textToProcess.isBlank()) return

            this.currentTurn?.userQuery = textToProcess

            DashboardManager.sendChat(isUser = true, text = textToProcess)

            logTurnState("User Text Appended", this.currentTurn)

            sendToApp(WebSocketPayloads.sttTranscriptCompleted(textToProcess))
            lastKnownUserQuery = textToProcess
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

        val qiContextAny = pipelineContext.activeToolContext?.qiContext
        AraWisperManager.speechManager.flush(qiContextAny) { pcmBytes ->
            val base64Audio = android.util.Base64.encodeToString(pcmBytes, android.util.Base64.NO_WRAP)
            serverScope.launch {
                pipelineContext.sendPacket(WebSocketPayloads.audioDelta(pipelineContext.responseId, base64Audio))
                pipelineContext.sendPacket(WebSocketPayloads.audioDone())
            }
        }
        pipelineContext.sendPacket(WebSocketPayloads.done())

        if (!isWaitingForAppToolResult) {
            this.currentTurn?.let { turn ->

                // Log.i(ProxySettingsRepository.TAG, "READY FOR DB: ${turn.turnId} | Q: ${turn.userQuery} | A: ${turn.llmResponse} | Tool: ${turn.toolCalled} | ToolBuffer: ${turn.toolBufferResponse}")

                DashboardManager.sendChat(isUser = false, text = turn.llmResponse)

                DataBaseClient.updateHistory(turn)

            }
            logTurnState("Finished", this.currentTurn)
        }

    }
    
    suspend fun recreateSession() {
        // Fetch the turns from your database
        val savedTurns = DataBaseClient.getUserHistory(currentUserId)

        for (turn in savedTurns) {
            // Recreate User message
            val userPayload = WebSocketPayloads.itemCreateUser(turn.turnId, turn.userQuery)
            sendToApp(userPayload)
            DashboardManager.sendChat(isUser = true, text = turn.userQuery)

            // Recreate LLM message
            val llmPayload = WebSocketPayloads.itemCreateLLM(turn.turnId + "_llm", turn.llmResponse)
            sendToApp(llmPayload)
            DashboardManager.sendChat(isUser = false, text = turn.llmResponse)

        }
    }

    private suspend fun processLlmText(llmText: String, pipelineContext: PipelineContext, isThinkingText: Boolean = false) {
        if (llmText.isNotBlank()) {
            pipelineContext.sendPacket(WebSocketPayloads.transcriptDelta(pipelineContext.responseId, llmText))

            if (!isThinkingText) {
                val qiContextAny = pipelineContext.activeToolContext?.qiContext
                AraWisperManager.speechManager.processTextChunk(qiContextAny, llmText) { pcmBytes ->
                    val base64Audio = android.util.Base64.encodeToString(pcmBytes, android.util.Base64.NO_WRAP)
                    serverScope.launch {
                        pipelineContext.sendPacket(WebSocketPayloads.audioDelta(pipelineContext.responseId, base64Audio))
                    }
                }
            }
            
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


