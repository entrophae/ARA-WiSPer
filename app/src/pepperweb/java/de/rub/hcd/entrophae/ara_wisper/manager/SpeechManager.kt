package de.rub.hcd.entrophae.ara_wisper.manager

import android.content.Context
import de.rub.hcd.entrophae.ara_wisper.controller.SpeechController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SpeechManager {
    private val speechController = SpeechController()

    fun processTextChunk(text: String, onPcmReady: ((ByteArray) -> Unit)? = null) {
        speechController.processTextChunk( text, onPcmReady)
    }

    suspend fun flush(onPcmReady: ((ByteArray) -> Unit)? = null) {
        suspendCancellableCoroutine { continuation ->
            speechController.flush(
                onPcmReady = onPcmReady,
                onComplete = {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            )
        }
    }

    fun stop() {
        speechController.stopAll()
    }

    fun destroy() {
        speechController.destroy()
    }

}