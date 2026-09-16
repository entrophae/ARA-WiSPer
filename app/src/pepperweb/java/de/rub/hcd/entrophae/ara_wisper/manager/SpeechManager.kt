package de.rub.hcd.entrophae.ara_wisper.manager

import android.content.Context
import de.rub.hcd.entrophae.ara_wisper.controller.SpeechController

class SpeechManager(context: Context) {
    private val speechController = SpeechController(context)

    fun processTextChunk(qiContext: Any?, text: String, onPcmReady: ((ByteArray) -> Unit)? = null) {
        speechController.processTextChunk(qiContext, text, onPcmReady)
    }

    fun flush(qiContext: Any?, onPcmReady: ((ByteArray) -> Unit)? = null) {
        speechController.flush(qiContext, onPcmReady)
    }

    fun stop() {
        speechController.stopAll()
    }

    fun destroy() {
        speechController.destroy()
    }
}