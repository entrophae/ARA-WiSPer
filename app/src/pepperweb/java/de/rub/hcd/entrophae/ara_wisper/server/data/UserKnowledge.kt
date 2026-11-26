package de.rub.hcd.entrophae.ara_wisper.server.data

/**
 * Data model representing a user's persistent knowledge profile and turn history.
 *
 * Used when synchronizing user memory summaries and conversation records between
 * [DataBaseClient] and the web dashboard's knowledge editor.
 */
data class UserKnowledge(
    val userId: String,
    val chatSummary: String,
    val history: List<ChatTurn>
)