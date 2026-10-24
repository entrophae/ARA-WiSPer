package de.rub.hcd.entrophae.ara_wisper.server.routes

import android.util.Log
import com.google.gson.Gson
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.manager.DashboardManager
import de.rub.hcd.entrophae.ara_wisper.server.network.DataBaseClient
import de.rub.hcd.entrophae.ara_wisper.server.network.RagClient
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import org.json.JSONObject

fun Route.ragRouting() {
    get(ProxySettingsRepository.Routes.RAG_USERS) {
        val userId = call.request.queryParameters["userId"]

        if (userId != null) {
            // If a userId is provided, return their full UserKnowledge object
            Log.d("ARA_DEBUG", "Returning full Knowledge of: $userId")
            val knowledge = DataBaseClient.getUserKnowledge(userId)
            call.respond(Gson().toJson(knowledge))
        } else {
            // If no userId is provided, return the list of all users
            Log.d("ARA_DEBUG", "Returning all users")
            val users = DataBaseClient.getAllUsers()
            call.respond(Gson().toJson(users))
        }
    }

    route(ProxySettingsRepository.Routes.RAG_EXPERIMENTS) {

        get {
            val experiments = DataBaseClient.getExperiments()
            call.respondText(experiments.toString(), ContentType.Application.Json)
        }

        post {
            try {
                val json = JSONObject(call.receiveText())
                val expId = json.optString("experimentId")
                val name = json.optString("name")

                if (expId.isNotBlank() && name.isNotBlank()) {
                    DataBaseClient.createExperiment(expId, name)
                    call.respondText("""{"status":"success"}""", ContentType.Application.Json)
                } else {
                    call.respondText("""{"status":"error", "message":"Missing data"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                }
            } catch (e: Exception) {
                call.respondText("""{"status":"error"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }

        delete("/{expId}") {
            val expId = call.parameters["expId"]
            if (expId.isNullOrBlank()) {
                call.respondText("""{"status":"error", "message":"Missing experiment ID"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@delete
            }

            try {
                val success = DataBaseClient.deleteExperiment(expId)

                if (success) {
                    call.respondText("""{"status":"success"}""", ContentType.Application.Json)
                } else {
                    call.respondText("""{"status":"error", "message":"Failed to delete experiment"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                }
            } catch (e: Exception) {
                Log.e(ProxySettingsRepository.TAG, "Delete Experiment Error: ${e.message}")
                call.respondText("""{"status":"error", "message":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        post("{id}/users") {
            val expId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val json = JSONObject(call.receiveText())
            val userId = json.optString("userId")

            if (userId.isNotBlank()) {
                DataBaseClient.assignUserToExperiment(expId, userId)
                call.respondText("""{"status":"success"}""", ContentType.Application.Json)
            }
        }

        delete("{id}/users/{userId}") {
            val expId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val userId = call.parameters["userId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)

            DataBaseClient.removeUserFromExperiment(expId, userId)
            call.respondText("""{"status":"success"}""", ContentType.Application.Json)
        }

        get("{id}/files") {
            val expId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val files = DataBaseClient.getExperimentFiles(expId)
            call.respondText(org.json.JSONArray(files).toString(), ContentType.Application.Json)
        }

        post("{id}/files") {
            val expId = call.parameters["id"]

            if (expId.isNullOrBlank()) {
                call.respondText("""{"status":"error", "message":"Missing experiment ID"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            var fileName = ""
            var fileBytes: ByteArray? = null

            try {

                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        fileName = part.originalFileName ?: "uploaded_file"
                        fileBytes = part.streamProvider().readBytes()
                    }
                    part.dispose()
                }

                if (fileBytes != null && fileName.isNotBlank()) {
                    val success = RagClient.ingestFile(expId, fileName, fileBytes)

                    if (success) {
                        call.respondText("""{"status":"success"}""", ContentType.Application.Json)
                    } else {
                        call.respondText("""{"status":"error", "message":"Failed to ingest file"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                } else {
                    call.respondText("""{"status":"error", "message":"No file received"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                }
            } catch (e: Exception) {
                Log.e(ProxySettingsRepository.TAG, "File Upload Error: ${e.message}")
                call.respondText("""{"status":"error", "message":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        get("{id}/files/{filename}/content") {
            val expId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val fileName = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val content = DataBaseClient.getFileContent(expId, fileName)
            call.respondText(JSONObject().put("content", content).toString(), ContentType.Application.Json)
        }


        get("{id}/files/{filename}/file") {
            val expId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val fileName = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)

            val fileData = RagClient.getFile(expId, fileName)
            if (fileData != null) {
                val (bytes, contentType) = fileData
                call.respondBytes(bytes, ContentType.parse(contentType))
            } else {
                call.respond(HttpStatusCode.NotFound, "File not found")
            }
        }

        delete("{id}/files/{filename}") {
            val expId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val fileName = call.parameters["filename"] ?: return@delete  call.respond(HttpStatusCode.BadRequest)
            val success = DataBaseClient.deleteExperimentFile(expId, fileName)
            if (success) {
                call.respondText("""{"status":"success"}""", ContentType.Application.Json)
            } else {
                call.respondText("""{"status":"error"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        get("{id}/progress") {
            val expId = call.parameters["id"]

            if (expId.isNullOrBlank()) {
                call.respondText("""{"status":"error", "message":"Missing experiment ID"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }
            val fileName = call.request.queryParameters["fileName"] ?: ""

            val progressJson = RagClient.getProgress(expId, fileName)
            call.respondText(progressJson, ContentType.Application.Json)
        }

    }

}
