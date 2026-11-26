package de.rub.hcd.entrophae.ara_wisper.network

import okhttp3.Request

/**
 * Ktor HTTP client extensions for attaching API authentication headers.
 *
 * Standardizes `Bearer` token and optional API key injection across outgoing requests.
 */
fun Request.Builder.authToken(apiKey: String) =
    this.addHeader("Authorization", "token $apiKey")
fun Request.Builder.authBearer(apiKey: String) =
    this.addHeader("Authorization", "Bearer $apiKey")