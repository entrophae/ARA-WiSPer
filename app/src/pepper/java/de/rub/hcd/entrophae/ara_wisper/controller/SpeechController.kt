package de.rub.hcd.entrophae.ara_wisper.controller

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.SayBuilder
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hardware QiSDK speech driver for the physical Pepper robot.
 *
 * Queues and executes `Say` actions on Pepper's onboard TTS engine, tracks in-flight
 * utterances for [SpeechManager] flush synchronization, and supports immediate cancellation.
 */
class SpeechController {
    private val queueLock = Any()
    private var activeSayFuture: Future<Void>? = null

    private val speechGeneration = AtomicInteger(0)
    private val inFlightUtterances = AtomicInteger(0)

    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null

    fun speak(sentence: String, onPcmReady: ((ByteArray) -> Unit)? = null) {
        if (sentence.isBlank()) return

        val context = AraWisperManager.chatActivityAdapter?.qiContext as? QiContext
        if (context == null) {
            Log.w(TAG, "Cannot speak '$sentence': qiContext is null")
            return
        }

        val generation = speechGeneration.get()
        if (inFlightUtterances.getAndIncrement() == 0) {
            onSpeakingStateChanged?.invoke(true)
        }

        val startSay = fun(): Future<Void>? {
            if (speechGeneration.get() != generation) return null
            val buildFuture = SayBuilder.with(context)
                .withText(sentence)
                .buildAsync() ?: return null

            return buildFuture.andThenCompose { say ->
                if (speechGeneration.get() != generation || say == null) {
                    null
                } else {
                    Log.i(TAG, "Saying: '$sentence'")
                    say.async().run()
                }
            }
        }

        val chainedFuture: Future<Void>? = synchronized(queueLock) {
            val previous = activeSayFuture
            val next = if (previous == null || previous.isDone) {
                startSay()
            } else {
                previous.thenCompose<Void> { startSay() }
            }
            activeSayFuture = next
            next
        }

        if (chainedFuture == null) {
            onUtteranceFinished(generation)
            return
        }

        chainedFuture.thenConsume { future ->
            if (future.hasError() && !future.isCancelled) {
                Log.e(TAG, "QiSDK Say failed for '$sentence': ${future.errorMessage}")
            }
            onUtteranceFinished(generation)
        }
    }

    fun flush(onComplete: () -> Unit) {
        val future = synchronized(queueLock) { activeSayFuture }
        if (future == null || future.isDone || inFlightUtterances.get() == 0) {
            onComplete()
        } else {
            future.thenConsume {
                onComplete()
            }
        }
    }

    fun stopAll() {
        speechGeneration.incrementAndGet()
        inFlightUtterances.set(0)

        val futureToCancel = synchronized(queueLock) {
            val current = activeSayFuture
            activeSayFuture = null
            current
        }

        if (futureToCancel != null && !futureToCancel.isDone) {
            try {
                futureToCancel.requestCancellation()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel speech future", e)
            }
        }
        onSpeakingStateChanged?.invoke(false)
    }

    fun destroy() {
        stopAll()
    }

    private fun onUtteranceFinished(generation: Int) {
        if (speechGeneration.get() != generation) return
        val remaining = inFlightUtterances.updateAndGet { current ->
            if (current > 0) current - 1 else 0
        }
        if (remaining == 0) {
            onSpeakingStateChanged?.invoke(false)
        }
    }

    companion object {
        private const val TAG = "SpeechController[Pepper]"
    }

}