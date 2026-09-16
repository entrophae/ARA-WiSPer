package de.rub.hcd.entrophae.ara_wisper.server.routes

import ch.fhnw.pepper_realtime.ui.ChatViewModel
import io.ktor.server.routing.Route
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import org.json.JSONObject

fun Route.constantsRouting(viewModel: ChatViewModel) {
    get(ProxySettingsRepository.Routes.CONSTANTS) {
        // Map your Kotlin constants to the keys your JS expects
        val constants = mapOf(
            // Dashboard Message Types
            "MSG_${ProxySettingsRepository.Dashboard.Events.LOAD_API_KEYS}" to ProxySettingsRepository.Dashboard.Events.LOAD_API_KEYS,

            "MSG_${ProxySettingsRepository.Dashboard.Events.STATUS_UPDATE}" to ProxySettingsRepository.Dashboard.Events.STATUS_UPDATE,

            "MSG_${ProxySettingsRepository.Dashboard.Events.CHAT_UPDATE}" to ProxySettingsRepository.Dashboard.Events.CHAT_UPDATE,

            "MSG_${ProxySettingsRepository.Dashboard.Events.SAVE_API_KEYS}" to ProxySettingsRepository.Dashboard.Events.SAVE_API_KEYS,

            "MSG_${ProxySettingsRepository.Dashboard.Events.CHAT_COMMAND}" to ProxySettingsRepository.Dashboard.Events.CHAT_COMMAND,

            "MSG_${ProxySettingsRepository.Dashboard.Events.SET_USER}" to ProxySettingsRepository.Dashboard.Events.SET_USER,

            "MSG_${ProxySettingsRepository.Dashboard.Events.DELETE_USER}" to ProxySettingsRepository.Dashboard.Events.DELETE_USER,

            "MSG_${ProxySettingsRepository.Dashboard.Events.UPDATE_USER_KNOWLEDGE}" to ProxySettingsRepository.Dashboard.Events.UPDATE_USER_KNOWLEDGE,

            "MSG_${ProxySettingsRepository.Dashboard.Events.CLEAR_HISTORY}" to ProxySettingsRepository.Dashboard.Events.CLEAR_HISTORY,

            "MSG_${ProxySettingsRepository.Logging.TYPE_PROXY_LOG}" to ProxySettingsRepository.Logging.TYPE_PROXY_LOG,

            // Routing Modes
            "MODE_${ProxySettingsRepository.Modes.ROUTING.REALTIME.split('_')[0].uppercase()}" to ProxySettingsRepository.Modes.ROUTING.REALTIME,

            "MODE_${ProxySettingsRepository.Modes.ROUTING.AZURE.split('_')[0].uppercase()}" to ProxySettingsRepository.Modes.ROUTING.AZURE,

            "MODE_${ProxySettingsRepository.Modes.ROUTING.CUSTOM.split('_')[0].uppercase()}" to ProxySettingsRepository.Modes.ROUTING.CUSTOM,

            // Routes
            "ROUTE_${ProxySettingsRepository.Routes.WS.trim('/').replace('/', '_').uppercase()}" to ProxySettingsRepository.Routes.WS
        )

        // Respond with the JSON object
        call.respondText(JSONObject(constants).toString(), ContentType.Application.Json)
    }
}