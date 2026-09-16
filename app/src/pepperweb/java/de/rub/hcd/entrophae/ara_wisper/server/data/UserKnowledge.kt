package de.rub.hcd.entrophae.ara_wisper.server.data

data class UserKnowledge(
    val userId: String,
    val chatSummary: String,
    val history: List<ChatTurn>
)