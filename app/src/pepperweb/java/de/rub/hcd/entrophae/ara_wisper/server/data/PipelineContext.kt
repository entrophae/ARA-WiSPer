package de.rub.hcd.entrophae.ara_wisper.server.data

import ch.fhnw.pepper_realtime.tools.ToolContext
import de.rub.hcd.entrophae.ara_wisper.interceptor.ToolInterceptor
import org.json.JSONArray

data class PipelineContext(
    val responseId: String,
    val systemPrompt: String,
    val toolsArray: JSONArray?,
    val apiSupportsTools: Boolean,
    val toolInterceptor: ToolInterceptor,
    val activeToolContext: ToolContext?,
    val checkCancelled: () -> Boolean,
    val sendPacket: suspend (String) -> Unit
)