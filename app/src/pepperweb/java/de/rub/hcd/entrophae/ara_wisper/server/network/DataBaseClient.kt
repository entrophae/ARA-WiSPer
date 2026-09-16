package de.rub.hcd.entrophae.ara_wisper.server.network

import android.util.Log
import ch.fhnw.pepper_realtime.network.HttpClientManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.UserKnowledge
import de.rub.hcd.entrophae.ara_wisper.server.manager.DashboardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object DataBaseClient {
    private val client = HttpClientManager.getInstance().getQuickApiClient()

    /**
     * Initializes a user in the database if they don't exist yet.
     */
    suspend fun addUser(userId: String) = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS}"

        try {
            val jsonBody = JSONObject().apply {
                put("userId", userId)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder().url(targetUrl).authToken(apiKey).post(jsonBody).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(
                        ProxySettingsRepository.TAG,
                        "DB: Registered empty profile for '$userId' in Vault"
                    )
                } else {
                    Log.e(
                        ProxySettingsRepository.TAG,
                        "DB: REST rejected user creation - Code: ${response.code}"
                    )
                    Log.e(ProxySettingsRepository.TAG, "DB: response: $response")
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Network Error (Create User): ${e.message}")
        }
    }
    suspend fun deleteUser(userId: String) = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS}?userId=$userId"

        try {
            val request = Request.Builder().url(targetUrl).authToken(apiKey).delete().build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(
                        ProxySettingsRepository.TAG,
                        "DB: Deleted profile for '$userId' in Vault"
                    )
                } else {
                    Log.e(
                        ProxySettingsRepository.TAG,
                        "DB: REST rejected user deletion - Code: ${response.code}"
                    )
                    Log.e(ProxySettingsRepository.TAG, "DB: response: $response")
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Network Error (Delete User): ${e.message}")
        }
    }

    /**
     * Saves a completed turn to the user's history.
     * Also updates an existing turn when edited.
     * Also deletes an existing turn when deleted
     */
    suspend fun updateHistory(turn: ChatTurn) = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS_TURN}"

        try {
            val jsonBody = JSONObject().apply {
                put("userId", turn.userId)
                put("turnId", turn.turnId)
                put("userQuery", turn.userQuery)
                put("llmResponse", turn.llmResponse)
                put("toolCalled", turn.toolCalled ?: JSONObject.NULL)
                put("toolBufferResponse", turn.toolBufferResponse ?: JSONObject.NULL)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder().url(targetUrl).authToken(apiKey).post(jsonBody).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(ProxySettingsRepository.TAG, "DB: Saved Turn [${turn.turnId}] to Vault.")
                    DashboardManager.syncHistoryToBrowser(turn.userId)
                } else {
                    Log.e(
                        ProxySettingsRepository.TAG,
                        "DB: REST rejected save - Code: ${response.code}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Network Error (Save): ${e.message}")
        }
    }

    suspend fun deleteTurn(userId: String, turnId: String) = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS_TURN}?userId=$userId&turnId=$turnId"

        try {

            val request = Request.Builder().url(targetUrl).authToken(apiKey).delete().build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(
                        ProxySettingsRepository.TAG,
                        "DB: Deleted turn for '$userId' in Vault"
                    )
                } else {
                    Log.e(
                        ProxySettingsRepository.TAG,
                        "DB: REST rejected turn deletion - Code: ${response.code}"
                    )
                    Log.e(ProxySettingsRepository.TAG, "DB: response: $response")
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Network Error (Delete Turn): ${e.message}")
        }
    }
    suspend fun deleteHistory(userId: String) = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS_HISTORY}?userId=$userId"

        try {

            val request = Request.Builder().url(targetUrl).authToken(apiKey).delete().build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(
                        ProxySettingsRepository.TAG,
                        "DB: Deleted history for '$userId' in Vault"
                    )
                } else {
                    Log.e(
                        ProxySettingsRepository.TAG,
                        "DB: REST rejected history deletion - Code: ${response.code}"
                    )
                    Log.e(ProxySettingsRepository.TAG, "DB: response: $response")
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Network Error (Delete History): ${e.message}")
        }
    }

    suspend fun updateSummary(userId: String, newSummary: String) = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS_SUMMARY}"

        try {
            val jsonBody = JSONObject().apply {
                put("userId", userId)
                put("summary", newSummary)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder().url(targetUrl).authToken(apiKey).put(jsonBody).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(ProxySettingsRepository.TAG, "DB: Saved new summary for '$userId'")
                }
                else {
                    Log.e(
                        ProxySettingsRepository.TAG,
                        "DB: REST rejected summary creation - Code: ${response.code}"
                    )
                    Log.e(ProxySettingsRepository.TAG, "DB: response: $response")
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Network Error (Summary): ${e.message}")
        }
    }
    suspend fun deleteSummary(userId: String) = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS_SUMMARY}?userId=$userId"

        try {
            val request = Request.Builder().url(targetUrl).authToken(apiKey).delete().build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(ProxySettingsRepository.TAG, "DB: Deleted summary for '$userId'")
                }
                else {
                    Log.e(
                        ProxySettingsRepository.TAG,
                        "DB: REST rejected summary deletion - Code: ${response.code}"
                    )
                    Log.e(ProxySettingsRepository.TAG, "DB: response: $response")
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Network Error (Delete Summary): ${e.message}")
        }

    }

    suspend fun getUserKnowledge(userId: String): UserKnowledge = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS_KNOWLEDGE}/$userId"

        val turnList = mutableListOf<ChatTurn>()
        var summary = ""

        try {
            val request = Request.Builder().url(targetUrl).authToken(apiKey).get().build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val jsonResponse = JSONObject(response.body!!.string())

                    // Extract Summary
                    summary = jsonResponse.optString("chatSummary", "")

                    // Extract History Array
                    val historyArray = jsonResponse.optJSONArray("history") ?: JSONArray()
                    for (i in 0 until historyArray.length()) {
                        val obj = historyArray.getJSONObject(i)
                        turnList.add(
                            ChatTurn(
                                userId = obj.optString("userId"),
                                turnId = obj.optString("turnId"),
                                userQuery = obj.optString("userQuery"),
                                llmResponse = obj.optString("llmResponse"),
                                toolCalled = if (obj.isNull("toolCalled")) null else obj.optString("toolCalled"),
                                toolBufferResponse = if (obj.isNull("toolBufferResponse")) null else obj.optString("toolBufferResponse")
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Fetch Error: ${e.message}")
        }

        return@withContext UserKnowledge(userId, summary, turnList)
    }
    /**
     * Retrieves the chat history for a specific user.
     * Useful for your recreateSession() method!
     */
    suspend fun getUserHistory(userId: String): List<ChatTurn> = withContext(Dispatchers.IO) {
        return@withContext getUserKnowledge(userId).history
    }

    /**
     * Retrieves the chat summary for a specific user.
     * Useful for your longterm memory!
     */
    suspend fun getUserSummary(userId: String): String = withContext(Dispatchers.IO) {
        return@withContext getUserKnowledge(userId).chatSummary
    }

    suspend fun getAllUsers(): List<String> = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS}"

        val userList = mutableListOf<String>()
        try {
            val request = Request.Builder().url(targetUrl).authToken(apiKey).get().build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val jsonArray = JSONArray(response.body!!.string())

                    // Unpack the JSON Array into our Kotlin List
                    for (i in 0 until jsonArray.length()) {
                        userList.add(jsonArray.getString(i))
                    }
                } else {
                    Log.e(
                        ProxySettingsRepository.TAG,
                        "DB: REST rejected getAllUsers - Code: ${response.code}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Fetch Error (GetAllUsers): ${e.message}")
        }

        return@withContext userList

    }
}