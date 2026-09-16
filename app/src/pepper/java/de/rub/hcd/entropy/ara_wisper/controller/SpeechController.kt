package de.rub.hcd.entrophae.ara_wisper.controller

import android.content.Context
import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.SayBuilder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SpeechController(private val context: Context) {
    private val sentenceBuffer = java.lang.StringBuilder()
    private var activeSayFuture: Future<Void>? = null

    fun processTextChunk(qiContextAny: Any?, text: String, onPcmReady: ((ByteArray) -> Unit)?) {
        if (text.isBlank()) return

        sentenceBuffer.append(text)
        val currentText = sentenceBuffer.toString()

        val lastPunctuation = maxOf(
            currentText.lastIndexOf('.'),
            currentText.lastIndexOf('!'),
            currentText.lastIndexOf('?')
        )

        if (lastPunctuation != -1) {
            val sentenceToSpeak = currentText.substring(0, lastPunctuation + 1)
            sentenceBuffer.delete(0, lastPunctuation + 1)
            speakSentence(qiContextAny, sentenceToSpeak, onPcmReady)
        }
    }

    fun flush(qiContextAny: Any?, onPcmReady: ((ByteArray) -> Unit)?) {
        val leftoverText = sentenceBuffer.toString()
        if (leftoverText.isNotBlank()) {
            speakSentence(qiContextAny, leftoverText, onPcmReady)
            sentenceBuffer.clear()
        }
    }

    private fun speakSentence(qiContextAny: Any?, sentence: String, onPcmReady: ((ByteArray) -> Unit)?) {
        if (sentence.isBlank()) return
        try {
            val qiContext = qiContextAny as? QiContext ?: return
            val sayAction = SayBuilder.with(qiContext).withText(sentence.trim()).build()

            val currentFuture = activeSayFuture
            if (currentFuture == null || currentFuture.isDone) {
                activeSayFuture = sayAction.async().run()
            } else {
                activeSayFuture = currentFuture.andThenCompose { sayAction.async().run() }
            }
        } catch (e: Exception) {
            Log.e("SpeechController", "Failed to build or run Say action", e)
        }
    }

    fun stopAll() {
        sentenceBuffer.clear()
        activeSayFuture?.let { future ->
            if (!future.isDone) {
                try { future.requestCancellation() } catch (e: Exception) { Log.w("SpeechController", "Failed to cancel speech future", e) }
            }
        }
        activeSayFuture = null
    }

    fun destroy() {
        stopAll()
    }

    companion object {
        private const val TAG = "SpeechController[STUB]"
    }

}