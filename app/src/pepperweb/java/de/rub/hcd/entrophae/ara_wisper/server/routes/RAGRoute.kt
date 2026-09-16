package de.rub.hcd.entrophae.ara_wisper.server.routes

import android.util.Log
import com.google.gson.Gson
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.network.DataBaseClient
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.ContentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider

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
    
}