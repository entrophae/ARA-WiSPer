package de.rub.hcd.entrophae.ara_wisper.manager

import android.content.Context
import de.rub.hcd.entrophae.ara_wisper.controller.SpeechController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SpeechManager(context: Context) {
    private val speechController = SpeechController(context)

    fun processTextChunk(qiContext: Any?, text: String, onPcmReady: ((ByteArray) -> Unit)? = null) {
        speechController.processTextChunk(qiContext, text, onPcmReady)
    }

    suspend fun flush(qiContext: Any?, onPcmReady: ((ByteArray) -> Unit)? = null) {
        suspendCancellableCoroutine { continuation ->
            speechController.flush(
                qiContextAny = qiContext,
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