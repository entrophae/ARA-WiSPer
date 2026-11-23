package de.rub.hcd.entrophae.ara_wisper.server.data

import android.util.Log
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Temporarily saves turns of conversation to keep track of the current state before saving in a database.
 * A turn consists of the userQuery, llmResponse, toolCalled and the toolBufferResponse.
 * Identification exists via userId and turnId.
 * * Lifecycle:
 * - executePipeline() assigns the userQuery (or handleItemCreate() if manually given)
 * - handleToolCall() assigns the toolCalled
 * - processLlmText() assigns the llmResponse and toolBufferResponse when thinking is needed
 */
data class ChatTurn(
    val userId: String,
    val turnId: String,
    var userQuery: String = "",
    var userImageUrl: String? = null,
    var llmResponse: String = "",
    var toolsUsed: MutableList<ToolCallRecord> = mutableListOf(),
    var toolCalled: String? = null,
    var toolBufferResponse: String? = null,
    var audioBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    var isSystemEvent: Boolean = false
) {
    val completedAppTools: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val completedToolResults: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
    @Volatile var appToolRounds: Int = 0
    var queryWaitingForTool: String = ""

    fun addToolCall(toolCallId: String, toolName: String, query: String) {
        this.toolsUsed.add(ToolCallRecord(callId = toolCallId, name = toolName, query = query))
    }

    fun addToolAnswer(toolCallId: String? = null, answer: String) {
        val record = toolCallId?.takeIf { it.isNotBlank() }?.let { id ->
            toolsUsed.find { it.callId == id }
        } ?: toolsUsed.lastOrNull()

        record?.answer = answer
    }

    fun resetToolState() {
        completedAppTools.clear()
        completedToolResults.clear()
        appToolRounds = 0
        queryWaitingForTool = ""
    }

    fun appendLlmText(llmText: String) {
        /*
        if (isThinkingText) {
            val currentBuffer = this.toolBufferResponse ?: ""
            val separator = if (currentBuffer.isNotEmpty() && !currentBuffer.endsWith("\n")) "\n\n" else ""
            this.toolBufferResponse = currentBuffer + separator + llmText

            Log.i(ProxySettingsRepository.TAG, "thought Added for Tool ${this.toolCalled}")
        } else {
            this.llmResponse += llmText
        }
         */
        this.llmResponse += llmText
        logTurnState("LLM Text Appended", this)
    }

    companion object {
        fun resolve(
            currentTurn: ChatTurn?,
            userId: String,
            turnId: String,
            text: String,
            isSystem: Boolean = false
        ): ChatTurn {
            if (currentTurn != null && currentTurn.turnId == turnId) {
                return currentTurn
            }

            return ChatTurn(
                userId = userId,
                turnId = turnId,
                userQuery = text,
                isSystemEvent = isSystem
            )
        }
    }
}

data class ToolCallRecord(
    val callId: String,
    val name: String,
    val query: String,
    var answer: String = ""
)

fun logTurnState(reason: String, turn: ChatTurn?) {
    turn?.let {
        Log.d(ProxySettingsRepository.TAG, "Turn State Update [$reason]: " +
                "ID=${it.turnId}, " +
                "UserTextLength=${it.userQuery.length}, " +
                "LLMTextLength=${it.llmResponse.length}, " +
                "Tool=${it.toolCalled ?: "None"}, " +
                "ToolBufferTextLength=${it.toolBufferResponse?.length ?: "0"}"
        )
    } ?: Log.d(ProxySettingsRepository.TAG, "Turn State Update [$reason]: currentTurn is NULL")
}