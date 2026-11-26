package de.rub.hcd.entrophae.ara_wisper.controller

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android `TextToSpeech` driver for standalone tablet/emulator execution.
 *
 * Synthesizes sentences either into temporary WAV/PCM buffers for `AudioPlayer` streaming
 * or directly through device speakers while tracking utterance completion and cancellation.
 */
class SpeechController {
    private data class TtsTask(
        val generation: Int,
        val tempFile: File?,
        val onPcmReady: ((ByteArray) -> Unit)?
    )

    private val lock = Any()
    private val speechGeneration = AtomicInteger(0)
    private val pendingTasks = ConcurrentHashMap<String, TtsTask>()

    private var textToSpeech: TextToSpeech? = null
    @Volatile
    private var isTtsInitialized = false
    private var onFlushComplete: (() -> Unit)? = null

    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null

    init {
        textToSpeech = TextToSpeech(AraWisperManager.appContext) { status ->
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
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                val task = id?.let { pendingTasks[it] } ?: return
                if (task.generation == speechGeneration.get()) {
                    onSpeakingStateChanged?.invoke(true)
                }
            }

            override fun onDone(id: String?) {
                handleTaskCompletion(id, isError = false)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                handleTaskCompletion(id, isError = true)
            }
        })
    }

    fun speak(sentence: String, onPcmReady: ((ByteArray) -> Unit)? = null) {
        if (!isTtsInitialized || textToSpeech == null || sentence.isBlank()) return

        val cleanText = sentence
            .replace("*", "")
            .replace(Regex("[\\x{10000}-\\x{10FFFF}]"), "")
            .trim()
        if (cleanText.isEmpty()) return

        val generation = speechGeneration.get()
        val utteranceId = "utterance_${System.nanoTime()}"

        if (onPcmReady != null) {
            val tempFile = File(AraWisperManager.appContext.cacheDir, "tts_${System.nanoTime()}.wav")
            pendingTasks[utteranceId] = TtsTask(generation, tempFile, onPcmReady)
            textToSpeech?.synthesizeToFile(cleanText, Bundle(), tempFile, utteranceId)
        } else {
            pendingTasks[utteranceId] = TtsTask(generation, tempFile = null, onPcmReady = null)
            textToSpeech?.speak(cleanText, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    private fun handleTaskCompletion(id: String?, isError: Boolean) {
        val task = id?.let { pendingTasks.remove(it) } ?: return
        val isCurrentGen = (task.generation == speechGeneration.get())

        if (task.tempFile != null) {
            if (!isError && isCurrentGen && task.tempFile.exists()) {
                try {
                    val fileBytes = task.tempFile.readBytes()
                    val pcmData = if (fileBytes.size > 44) {
                        fileBytes.copyOfRange(44, fileBytes.size)
                    } else {
                        ByteArray(0)
                    }
                    if (pcmData.isNotEmpty()) {
                        task.onPcmReady?.invoke(pcmData)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read synthesized audio file", e)
                }
            }
            task.tempFile.delete()
        }

        val flushCallback: (() -> Unit)?
        val allFinished: Boolean
        synchronized(lock) {
            allFinished = pendingTasks.isEmpty()
            flushCallback = if (allFinished) {
                val cb = onFlushComplete
                onFlushComplete = null
                cb
            } else {
                null
            }
        }

        if (allFinished) {
            onSpeakingStateChanged?.invoke(false)
            flushCallback?.invoke()
        }
    }


    fun flush(onComplete: () -> Unit) {
        synchronized(lock) {
            if (pendingTasks.isEmpty()) {
                onComplete()
            } else {
                val previousCallback = onFlushComplete
                onFlushComplete = {
                    previousCallback?.invoke()
                    onComplete()
                }
            }
        }
    }


    fun stopAll() {
        speechGeneration.incrementAndGet()
        textToSpeech?.stop()

        val flushCallback: (() -> Unit)?
        synchronized(lock) {
            pendingTasks.values.forEach { it.tempFile?.delete() }
            pendingTasks.clear()
            flushCallback = onFlushComplete
            onFlushComplete = null
        }

        onSpeakingStateChanged?.invoke(false)
        flushCallback?.invoke()
    }

    fun destroy() {
        stopAll()
        textToSpeech?.shutdown()
    }

    companion object {
        private const val TAG = "SpeechController[STUB]"
    }
}