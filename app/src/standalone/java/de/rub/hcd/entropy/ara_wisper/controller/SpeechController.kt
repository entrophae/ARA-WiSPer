package de.rub.hcd.entrophae.ara_wisper.controller

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SpeechController(private val context: Context) {
    private val sentenceBuffer = java.lang.StringBuilder()
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false

    private val pendingTtsTasks = ConcurrentHashMap<String, Pair<File, (ByteArray) -> Unit>>()

    init {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.GERMAN)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsInitialized = true
                    setupTtsListener()
                } else {
                    Log.e("SpeechController", "TTS Language is not supported")
                }
            } else {
                Log.e("SpeechController", "TTS Initialization failed")
            }
        }
    }

    private fun setupTtsListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                val task = pendingTtsTasks.remove(id)
                if (task != null) {
                    val tempFile = task.first
                    val onPcmReady = task.second

                    if (tempFile.exists()) {
                        try {
                            val fileBytes = tempFile.readBytes()
                            val pcmData = if (fileBytes.size > 44) fileBytes.copyOfRange(44, fileBytes.size) else ByteArray(0)
                            tempFile.delete()

                            if (pcmData.isNotEmpty()) {
                                onPcmReady(pcmData)
                            }
                        } catch (e: Exception) {
                            Log.e("SpeechController", "Failed to read synthesized audio file", e)
                            tempFile.delete()
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                val task = pendingTtsTasks.remove(id)
                task?.first?.delete()
            }
        })
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

    fun flush(qiContextAny: Any?, onPcmReady: ((ByteArray) -> Unit)?) {
        val leftoverText = sentenceBuffer.toString()
        if (leftoverText.isNotBlank()) {
            speakSentence(qiContextAny, leftoverText, onPcmReady)
            sentenceBuffer.clear()
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
    }

    fun destroy() {
        stopAll()
        textToSpeech?.shutdown()
    }

    companion object {
        private const val TAG = "SpeechController[STUB]"
    }
}