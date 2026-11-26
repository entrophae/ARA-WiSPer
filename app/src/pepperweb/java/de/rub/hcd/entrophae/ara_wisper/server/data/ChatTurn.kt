package de.rub.hcd.entrophae.ara_wisper.server.data

import android.util.Log
import de.rub.hcd.entrophae.ara_wisper.interceptor.ToolSynchronizer
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.manager.DashboardManager
import de.rub.hcd.entrophae.ara_wisper.server.network.DataBaseClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mutable per-turn state container tracking a single user-assistant interaction.
 *
 * Accumulates user transcripts, camera/image attachments, assistant text, synthesized
 * audio buffers, and asynchronous tool call records before persisting the finalized
 * turn to [DataBaseClient] and [DashboardManager].
 */
data class ChatTurn(
    val userId: String,
    val turnId: String,
    var userQuery: String = "",
    var userImageUrl: String? = null,
    var llmResponse: String = "",
    var toolsUsed: MutableList<ToolCallRecord> = CopyOnWriteArrayList(),
    var toolCalled: String? = null,
    var toolBufferResponse: String? = null,
    var audioBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    var isSystemEvent: Boolean = false
) {
    val pendingAppToolCallIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val completedAppTools: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val completedToolResults: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
    val toolSynchronizer: ToolSynchronizer = ToolSynchronizer()

    @Volatile var appToolRounds: Int = 0
    @Volatile var isCancelled: Boolean = false
    @Volatile var pendingPrompt: String = ""
    var queryWaitingForTool: String = ""

    val hasPendingAppTools: Boolean
        get() = pendingAppToolCallIds.isNotEmpty()

    fun consumePendingPrompt(): String = pendingPrompt.also { pendingPrompt = "" }

    fun addToolCall(toolCallId: String, toolName: String, query: String) {
        this.toolsUsed.add(ToolCallRecord(callId = toolCallId, name = toolName, query = query))
    }

    fun addToolAnswer(toolCallId: String? = null, answer: String) {
        val record = toolCallId?.takeIf { it.isNotBlank() }?.let { id ->
            toolsUsed.find { it.callId == id }
        } ?: toolsUsed.lastOrNull()

        record?.answer = answer
    }

    fun registerPendingAppTool(toolName: String, callId: String) {
        completedAppTools.add(toolName)
        pendingAppToolCallIds.add(callId)
    }

    fun completeAppTool(callId: String, answer: String): String {
        val toolName = toolsUsed.find { it.callId == callId }?.name ?: "tool"
        addToolAnswer(callId, answer)
        completedToolResults.add(toolName to answer)
        pendingAppToolCallIds.remove(callId)
        return toolName
    }

    fun resetToolState() {
        pendingAppToolCallIds.clear()
        completedAppTools.clear()
        completedToolResults.clear()
        toolSynchronizer.reset()
        appToolRounds = 0
        queryWaitingForTool = ""
        pendingPrompt = ""
    }

    fun cancel() {
        isCancelled = true
        resetToolState()
    }

    fun appendLlmText(llmText: String) {
        this.llmResponse += llmText
        logTurnState("LLM Text Appended", this)
    }

    suspend fun persist() {
        Log.i(
            ProxySettingsRepository.TAG,
            "READY FOR DB: $turnId | Q: $userQuery | A: $llmResponse | Tool: $toolCalled | ToolBuffer: $toolBufferResponse"
        )
        DashboardManager.sendChat(isUser = false, text = llmResponse)

        if (!isSystemEvent) {
            DataBaseClient.updateHistory(this)
        } else {
            Log.i(ProxySettingsRepository.TAG, "System event turn completed. Bypassing DB save to protect user knowledge.")
        }
    }

    companion object {
        fun resolve(
            currentTurn: ChatTurn?,
            turnId: String = "item_${System.currentTimeMillis()}",
            text: String = "",
            isSystem: Boolean = false
        ): ChatTurn {
            if (currentTurn != null && currentTurn.turnId == turnId) {
                if (text.isNotBlank()) currentTurn.userQuery = text
                return currentTurn
            }

            return ChatTurn(
                userId = ProxySettingsRepository.activeUserId,
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