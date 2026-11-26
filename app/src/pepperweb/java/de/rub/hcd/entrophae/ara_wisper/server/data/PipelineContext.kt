package de.rub.hcd.entrophae.ara_wisper.server.data

import de.rub.hcd.entrophae.ara_wisper.manager.TurnBridge

/**
 * Lightweight per-turn execution context passed across pipeline stages and tool calls.
 *
 * Bundles the persistent [ChatTurn] state, the evolving prompt accumulated across
 * successive tool rounds, and the outbound [TurnBridge] handle.
 */
data class PipelineContext(
    val turn: ChatTurn,
    var currentPrompt: String,
    val bridge: TurnBridge
)