package de.rub.hcd.entrophae.ara_wisper.server.network

import android.util.Log
import ch.fhnw.pepper_realtime.network.HttpClientManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.network.authToken
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.ToolCallRecord
import de.rub.hcd.entrophae.ara_wisper.server.data.UserKnowledge
import de.rub.hcd.entrophae.ara_wisper.server.manager.DashboardManager
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object DataBaseClient {
    private val client = HttpClientManager.getInstance().getQuickApiClient()


    suspend fun fetchStatus() = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint/api/status"

        try {
            val request = Request.Builder()
                .url(targetUrl)
                .authToken(apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                Log.i("DB HEALTH", response.toString())

                val contentType = response.header("Content-Type") ?: ""

                if (contentType.contains("text/html")) {
                    throw IOException("Intercepted by Shibboleth SSO instead of target endpoint")
                }

                if (response.isSuccessful && response.body != null) {
                    return@withContext response.body!!.string()
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Status Error: ${e.message}")
        }
        return@withContext """{"status":"error"}"""
    }

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
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS}/$userId"

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
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS}/${turn.userId}/history/turns"

        try {
            val toolsJsonArray =
                if (turn.toolsUsed.isEmpty()) JSONObject.NULL
                else {
                    JSONArray().apply {
                        for (record in turn.toolsUsed) {
                            put(JSONObject().apply {
                                put("callId", record.callId)
                                put("name", record.name)
                                put("query", record.query)
                                put("answer", record.answer)
                            })
                        }
                    }
                }

            val jsonBody = JSONObject().apply {
                put("userId", turn.userId)
                put("turnId", turn.turnId)
                put("userQuery", turn.userQuery)
                put("llmResponse", turn.llmResponse)
                put("userImageUrl", turn.userImageUrl ?: JSONObject.NULL)
                put("toolCalled", turn.toolCalled ?: JSONObject.NULL)
                put("toolBufferResponse", turn.toolBufferResponse ?: JSONObject.NULL)
                put("toolsUsed", toolsJsonArray)
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
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS}/$userId/history/turns/$turnId"

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
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS}/$userId/history"

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
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS}/$userId/summary"

        try {
            val jsonBody = JSONObject().apply {
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
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS}/$userId/summary"

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
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS}/$userId"

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

                        val rawToolsStr = obj.optString("toolsUsed")
                        val toolsUsedList = mutableListOf<ToolCallRecord>()

                        if (rawToolsStr.isNotBlank() && rawToolsStr != "null") {
                            try {
                                val toolsArray = JSONArray(rawToolsStr)
                                for (j in 0 until toolsArray.length()) {
                                    val tObj = toolsArray.getJSONObject(j)
                                    toolsUsedList.add(
                                        ToolCallRecord(
                                            callId = tObj.optString("callId", ""),
                                            name = tObj.optString("name", ""),
                                            query = tObj.optString("query", ""),
                                            answer = tObj.optString("answer", "")
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(ProxySettingsRepository.TAG, "Failed to parse toolsUsed for turn: ${e.message}")
                            }
                        }

                        turnList.add(
                            ChatTurn(
                                userId = obj.optString("userId"),
                                turnId = obj.optString("turnId"),
                                userQuery = obj.optString("userQuery"),
                                userImageUrl = if (obj.isNull("userImageUrl")) null else obj.optString("userImageUrl"),
                                llmResponse = obj.optString("llmResponse"),
                                toolCalled = if (obj.isNull("toolCalled")) null else obj.optString("toolCalled"),
                                toolBufferResponse = if (obj.isNull("toolBufferResponse")) null else obj.optString("toolBufferResponse"),
                                toolsUsed = toolsUsedList
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

    suspend fun getExperiments(): JSONArray = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_EXPERIMENTS}"

        try {
            val request = Request.Builder().url(targetUrl).authToken(apiKey).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    JSONArray(response.body!!.string())
                } else {
                    JSONArray()
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Fetch Error (Experiments): ${e.message}")
            JSONArray()
        }
    }

    suspend fun getExperimentIdForUser(userId: String): String = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_USERS}/$userId/experiments"

        try {
            val request = Request.Builder().url(targetUrl).authToken(apiKey).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val jsonArray = JSONArray(response.body!!.string())
                    if (jsonArray.length() > 0) {
                        return@withContext jsonArray.getString(0)
                    }
                } else {
                    Log.e(ProxySettingsRepository.TAG, "DB: REST rejected user experiments - Code: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Fetch Error (Experiment for User): ${e.message}")
        }
        return@withContext ""
    }

    suspend fun createExperiment(experimentId: String, name: String) = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_EXPERIMENTS}"

        try {
            val jsonBody = JSONObject().apply {
                put("experimentId", experimentId)
                put("name", name)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder().url(targetUrl).authToken(apiKey).post(jsonBody).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(ProxySettingsRepository.TAG, "DB: REST rejected create experiment - Code: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Network Error (Create Experiment): ${e.message}")
        }
    }

    suspend fun assignUserToExperiment(experimentId: String, userId: String) = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_EXPERIMENTS}/$experimentId/users"

        try {
            val jsonBody = JSONObject().put("userId", userId).toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(targetUrl).authToken(apiKey).post(jsonBody).build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Network Error (Assign User): ${e.message}")
        }
    }

    suspend fun removeUserFromExperiment(experimentId: String, userId: String) = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_EXPERIMENTS}/$experimentId/users/$userId"

        try {
            val request = Request.Builder().url(targetUrl).authToken(apiKey).delete().build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "DB Network Error (Remove User): ${e.message}")
        }
    }

    suspend fun getExperimentFiles(experimentId: String): List<String> = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_EXPERIMENTS}/$experimentId/documents"
        try {
            val request = Request.Builder().url(targetUrl).authToken(apiKey).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val json = JSONObject(response.body!!.string())
                    val arr = json.optJSONArray("files") ?: return@withContext emptyList()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) list.add(arr.getString(i))
                    return@withContext list
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "Error fetching files: ${e.message}")
        }
        return@withContext emptyList()
    }

    suspend fun getFileContent(experimentId: String, fileName: String): String = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_EXPERIMENTS}/$experimentId/files/$encodedName/content"
        Log.d(ProxySettingsRepository.TAG, "DB Request GET Content URL: $targetUrl")
        try {
            val request = Request.Builder().url(targetUrl).authToken(apiKey).get().build()
            client.newCall(request).execute().use { response ->
                Log.d(ProxySettingsRepository.TAG, "DB Response Code: ${response.code}")
                if (response.isSuccessful && response.body != null) {
                    val bodyStr = response.body!!.string()
                    val json = JSONObject(bodyStr)
                    return@withContext json.optString("content", "")
                } else {
                    Log.e(ProxySettingsRepository.TAG, "DB Error Response: ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "Error fetching content: ${e.message}", e)
        }
        return@withContext "Error loading file content."
    }

    suspend fun deleteExperimentFile(experimentId: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_EXPERIMENTS}/$experimentId/files/$encodedName"
        Log.d(ProxySettingsRepository.TAG, "DB Request DELETE URL: $targetUrl")
        try {
            val request = Request.Builder().url(targetUrl).authToken(apiKey).delete().build()
            client.newCall(request).execute().use { response ->
                Log.d(ProxySettingsRepository.TAG, "DB Delete Response Code: ${response.code}")
                if (!response.isSuccessful) {
                    Log.e(ProxySettingsRepository.TAG, "DB Delete Error Body: ${response.body?.string()}")
                }
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "Error deleting file: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun deleteExperiment(experimentId: String): Boolean = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_EXPERIMENTS}/$experimentId"
        Log.d(ProxySettingsRepository.TAG, "DB Request DELETE URL: $targetUrl")
        try {
            val request = Request.Builder().url(targetUrl).authToken(apiKey).delete().build()
            client.newCall(request).execute().use { response ->
                Log.d(ProxySettingsRepository.TAG, "DB Delete Response Code: ${response.code}")
                if (!response.isSuccessful) {
                    Log.e(ProxySettingsRepository.TAG, "DB Delete Error Body: ${response.body?.string()}")
                    Log.e(ProxySettingsRepository.TAG, "DB Delete Error Body: ${response.message}")
                }
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "Error deleting file: ${e.message}", e)
            return@withContext false
        }
    }


}