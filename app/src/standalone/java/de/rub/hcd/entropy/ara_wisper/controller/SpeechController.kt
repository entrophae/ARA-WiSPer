package de.rub.hcd.entrophae.ara_wisper.controller

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.remove

class SpeechController(private val context: Context) {
    private val lock = Any()
    private val sentenceBuffer = java.lang.StringBuilder()
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false

    private val pendingTtsTasks = ConcurrentHashMap<String, Pair<File, (ByteArray) -> Unit>>()
    private var onFlushComplete: (() -> Unit)? = null

    init {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.GERMAN)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsInitialized = true
                    setupTtsListener()
                } else {
                    Log.e(TAG, "TTS Language is not supported")
                }
            } else {
                Log.e(TAG, "TTS Initialization failed")
            }
        }
    }

    private fun setupTtsListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                handleTaskCompletion(id)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                handleTaskCompletion(id, isError = true)
            }
        })
    }

    private fun handleTaskCompletion(id: String?, isError: Boolean = false) {
        val task = pendingTtsTasks.remove(id)
        if (task != null) {
            val tempFile = task.first
            val onPcmReady = task.second
            if (!isError && tempFile.exists()) {
                try {
                    val fileBytes = tempFile.readBytes()
                    val pcmData = if (fileBytes.size > 44) fileBytes.copyOfRange(44, fileBytes.size) else ByteArray(0)
                    if (pcmData.isNotEmpty()) {
                        onPcmReady(pcmData)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read synthesized audio file", e)
                }
            }
            tempFile.delete()
        }
        synchronized(lock) {
            if (pendingTtsTasks.isEmpty()) {
                onFlushComplete?.invoke()
                onFlushComplete = null
            }
        }
    }

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

    fun flush(qiContextAny: Any?, onPcmReady: ((ByteArray) -> Unit)?, onComplete: (() -> Unit)? = null) {
        val leftoverText = sentenceBuffer.toString()
        if (leftoverText.isNotBlank()) {
            speakSentence(qiContextAny, leftoverText, onPcmReady)
            sentenceBuffer.clear()
        }

        synchronized(lock) {
            if (pendingTtsTasks.isEmpty()) {
                onComplete?.invoke()
            } else {
                onFlushComplete = onComplete
            }
        }
    }

    private fun speakSentence(qiContextAny: Any?, sentence: String, onPcmReady: ((ByteArray) -> Unit)?) {
        if (!isTtsInitialized || textToSpeech == null || sentence.isBlank()) return
        val cleanText = sentence.replace("*", "").replace(Regex("[\\x{10000}-\\x{10FFFF}]"), "")

        if (onPcmReady != null) {
            val tempFile = File(context.cacheDir, "tts_${System.nanoTime()}.wav")
            val utteranceId = "utterance_${System.nanoTime()}"

            pendingTtsTasks[utteranceId] = Pair(tempFile, onPcmReady)

            val params = android.os.Bundle()
            textToSpeech?.synthesizeToFile(cleanText.trim(), params, tempFile, utteranceId)
        } else {
            textToSpeech?.speak(cleanText.trim(), TextToSpeech.QUEUE_ADD, null, "SpeechControllerTTS")
        }
    }

    fun stopAll() {
        sentenceBuffer.clear()
        textToSpeech?.stop()
        pendingTtsTasks.values.forEach { it.first.delete() }
        pendingTtsTasks.clear()
        onFlushComplete = null
    }

    fun destroy() {
        stopAll()
        textToSpeech?.shutdown()
    }

    companion object {
        private const val TAG = "SpeechController[STUB]"
    }
}