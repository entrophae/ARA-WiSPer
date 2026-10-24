package de.rub.hcd.entrophae.ara_wisper.server.data

import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import org.json.JSONArray

data class PipelineContext(
    val responseId: String,
    val systemPrompt: String,
    val toolsArray: JSONArray?,
    val apiSupportsTools: Boolean,
    val checkCancelled: () -> Boolean,
    val sendPacket: suspend (String) -> Unit
)