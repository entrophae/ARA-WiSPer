package de.rub.hcd.entrophae.ara_wisper.server.network

import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import org.json.JSONArray
import org.json.JSONObject

object WebSocketPayloads {
    fun sessionCreated(): String {
        return JSONObject().apply {
            put(ProxySettingsRepository.Proxy.Keys.TYPE, ProxySettingsRepository.Proxy.Events.SESSION_CREATED)
        }.toString()
    }

    fun thinking(responseId: String): String {
        return JSONObject().apply {
            put(
                ProxySettingsRepository.Proxy.Keys.TYPE,
                ProxySettingsRepository.Proxy.Events.RESP_CREATED
            )
            put(
                ProxySettingsRepository.Proxy.Keys.RESPONSE,
                JSONObject().put(ProxySettingsRepository.Proxy.Keys.ID, responseId)
            )
        }.toString()
    }

    fun outputItemAdded(itemId: String): String {
        return JSONObject().apply {
            put("type", "response.output_item.added")
            put("item", JSONObject().apply {
                put("id", itemId)
                put("type", "message")
                put("role", "assistant")
            })
        }.toString()
    }

    fun transcriptDelta(responseId: String, text: String): String {
        return JSONObject().apply {
            put(
                ProxySettingsRepository.Proxy.Keys.TYPE,
                ProxySettingsRepository.Proxy.Events.RESP_TRANSCRIPT_DELTA
            )
            put(ProxySettingsRepository.Proxy.Keys.RESPONSE_ID, responseId)
            put(ProxySettingsRepository.Proxy.Keys.DELTA, text)
        }.toString()
    }

    fun audioDelta(responseId: String, base64Audio: String): String {
        return JSONObject().apply {
            put(ProxySettingsRepository.Proxy.Keys.TYPE, ProxySettingsRepository.Proxy.Events.RESP_AUDIO_DELTA)
            put(ProxySettingsRepository.Proxy.Keys.RESPONSE_ID, responseId)
            put(ProxySettingsRepository.Proxy.Keys.DELTA, base64Audio)
        }.toString()
    }

    fun audioDone(): String {
        return JSONObject().apply {
            put(ProxySettingsRepository.Proxy.Keys.TYPE, ProxySettingsRepository.Proxy.Events.RESP_AUDIO_DONE)
        }.toString()
    }

    fun toolCall(callId: String, name: String, args: String?): String {
        return JSONObject().apply {
            put(
                ProxySettingsRepository.Proxy.Keys.TYPE,
                ProxySettingsRepository.Proxy.Events.RESP_DONE
            )
            put("response", JSONObject().apply {
                put("output", JSONArray().put(JSONObject().apply {
                    put("type", "function_call")
                    put("call_id", callId)
                    put("name", name)
                    put("arguments", args)
                }))
            })
        }.toString()
    }

    fun done(): String {
        return JSONObject().apply {
            put(ProxySettingsRepository.Proxy.Keys.TYPE, ProxySettingsRepository.Proxy.Events.RESP_DONE)
        }.toString()
    }

    fun error(): String {
        return JSONObject().apply {
            put(ProxySettingsRepository.Proxy.Keys.TYPE, ProxySettingsRepository.Proxy.Events.RESP_DONE)
            put(ProxySettingsRepository.Proxy.Keys.RESPONSE, JSONObject().put("output", JSONArray()))
        }.toString()
    }

    fun sttTranscriptCompleted(text: String): String {
        return JSONObject().apply {
            put(ProxySettingsRepository.Proxy.Keys.TYPE, ProxySettingsRepository.Proxy.Events.CONV_ITEM_TRANSCRIPT_COMPLETED)
            put(ProxySettingsRepository.Proxy.Keys.ITEM_ID, "proxy_user_item_${System.currentTimeMillis()}")
            put(ProxySettingsRepository.Proxy.Keys.TRANSCRIPT, text)
        }.toString()
    }

    fun dashboardPayload(type: String, data: Any): JSONObject {
        return JSONObject().apply {
            put(ProxySettingsRepository.Logging.Keys.TYPE, type)
            put(ProxySettingsRepository.Logging.Keys.DATA, data)
        }
    }

    fun itemCreateUser(turnId: String, userQuery: String): String {
        return JSONObject().apply {
            put("type", "conversation.item.create")
            put("item", JSONObject().apply {
                put("id", turnId)
                put("type", "message")
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", userQuery)
                    })
                })
            })
        }.toString()
    }
    fun itemCreateLLM(itemId: Any, llmResponse: Any): String {
        return JSONObject().apply {
            put("type", "conversation.item.create")
            put("item", JSONObject().apply {
                put("id", itemId)
                put("type", "message")
                put("role", "assistant")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", llmResponse)
                    })
                })
            })
        }.toString()
    }

    fun userItemPlaceholder(itemId: String): String {
        return JSONObject().apply {
            put("type", "conversation.item.create")
            put("item", JSONObject().apply {
                put("id", itemId)
                put("type", "message")
                put("role", "user")
                put("content", JSONArray()) // Empty content = placeholder
            })
        }.toString()
    }
}