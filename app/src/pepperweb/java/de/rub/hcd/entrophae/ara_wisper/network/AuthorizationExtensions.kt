package de.rub.hcd.entrophae.ara_wisper.network

import okhttp3.Request

fun Request.Builder.authToken(apiKey: String) =
    this.addHeader("Authorization", "token $apiKey")
fun Request.Builder.authBearer(apiKey: String) =
    this.addHeader("Authorization", "Bearer $apiKey")