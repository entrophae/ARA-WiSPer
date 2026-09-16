package de.rub.hcd.entrophae.ara_wisper.server.data

import android.util.Log
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository

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
    var llmResponse: String = "",
    var toolCalled: String? = null,
    var toolBufferResponse: String? = null
)

fun createTurn(userId: String, turnId: String, userQuery: String, currentTurn: ChatTurn?): ChatTurn {
    if (currentTurn == null || currentTurn.turnId != turnId) {
        val turn = ChatTurn(
            userId = userId,
            turnId = turnId,
            userQuery = userQuery
        )
        logTurnState("Initialized", turn)
        return turn
    }
    return currentTurn
}

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