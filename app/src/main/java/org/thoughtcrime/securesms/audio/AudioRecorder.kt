package org.thoughtcrime.securesms.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.session.libsignal.utilities.Log
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "AudioRecorder"

data class AudioRecordResult(
    val file: File,
    val length: Long,
    val duration: Duration
)


class AudioRecorderHandle(
    private val onStopCommand: suspend () -> Unit,
    private val deferred: Deferred<Result<AudioRecordResult>>,
    private val startedResult: SharedFlow<Result<Unit>>,
) {

    private val listenerScope = CoroutineScope(Dispatchers.Main)

    /**
     * Add a listener that will be called on main thread, when the recording has started.
     *
     * Note that after stop/cancel is called, this listener will not be called again.
     */
    fun addOnStartedListener(onStartedResult: (Result<Unit>) -> Unit) {
        listenerScope.launch {
            startedResult.collectLatest { result ->
                onStartedResult(result)
            }
        }
    }

    /**
     * Stop the recording process and return the result. Note that if there's error
     * during the recording, this method will throw an exception.
     */
    suspend fun stop(): AudioRecordResult {
        listenerScope.cancel()
        onStopCommand()
        return deferred.await().getOrThrow()
    }

    /**
     * Cancel the recording process and discard any result.
     *
     * The cancellation is best effort only. When the method returns, there's no
     * guarantee that the recording has been stopped. But it's guaranteed that if you
     * spin up a new recording immediately after calling this method, the new recording session
     * won't start until the old one is properly cleaned up.
     */
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    fun cancel() {
        listenerScope.cancel()
        deferred.cancel()

        if (deferred.isCompleted && deferred.getCompleted().isSuccess) {
            // Clean up the temporary file if the recording was completed while we were cancelling.
            GlobalScope.launch {
                deferred.getCompleted().getOrThrow().file.delete()
            }
        }
    }
}

private sealed interface RecorderCommand {
    data object Stop : RecorderCommand
    data class ErrorReceived(val error: Throwable) : RecorderCommand
}

// There can only be on instance of MediaRecorder running at a time, we use a coroutine Mutex to ensure only
// one coroutine can access the MediaRecorder at a time.
private val mediaRecorderMutex = Mutex()

/**
 * Start recording audio. THe recording will be bound to the lifecycle of the coroutine scope.
 *
 * To stop recording and grab the result, call [AudioRecorderHandle.stop]
 */
fun recordAudio(
    scope: CoroutineScope,
    context: Context,
): AudioRecorderHandle {
    // Channel to send commands to the recorder coroutine.
    val commandChannel = Channel<RecorderCommand>(capacity = 1)

    // Channel to notify if the recording has started successfully.
    val startResultChannel = MutableSharedFlow<Result<Unit>>(replay = 1, extraBufferCapacity = 1)

    // Start the recording in a coroutine
    val deferred = scope.async(Dispatchers.IO) {
        runCatching {
            mediaRecorderMutex.withLock {
                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    MediaRecorder()
                }

                var started = false

                try {
                    val file by lazy {
                        File.createTempFile("audio_recording_", ".m4a", context.cacheDir)
                    }

                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                    recorder.setAudioChannels(1)
                    recorder.setAudioSamplingRate(44100)
                    recorder.setAudioEncodingBitRate(32000)
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setOutputFile(file)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setOnErrorListener { _, what, extra ->
                        commandChannel.trySend(
                            RecorderCommand.ErrorReceived(
                                RuntimeException("MediaRecorder error: what=$what, extra=$extra")
                            )
                        )
                    }

                    recorder.prepare()
                    recorder.start()
                    val recordingStarted = SystemClock.elapsedRealtime()
                    started = true
                    startResultChannel.emit(Result.success(Unit))

                    // Wait for either stop signal or error
                    when (val c = commandChannel.receive()) {
                        is RecorderCommand.Stop -> {
                            Log.d(TAG, "Received stop command, stopping recording.")
                            val duration =
                                (SystemClock.elapsedRealtime() - recordingStarted).milliseconds
                            recorder.stop()

                            val length = file.length()

                            return@runCatching AudioRecordResult(
                                file = file,
                                length = length,
                                duration = duration
                            )
                        }

                        is RecorderCommand.ErrorReceived -> {
                            Log.e(TAG, "Error received during recording: ${c.error.message}")
                            file.delete()
                            throw c.error
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        Log.d(TAG, "Recording cancelled by coroutine cancellation")
                    } else {
                        Log.e(TAG, "Error during audio recording", e)
                    }

                    if (!started) {
                        startResultChannel.emit(Result.failure(e))
                    }
                    throw e
                } finally {
                    Log.d(TAG, "Releasing MediaRecorder resources")
                    recorder.release()
                }
            }
        }
    }

    return AudioRecorderHandle(
        onStopCommand = { commandChannel.send(RecorderCommand.Stop) },
        deferred = deferred,
        startedResult = startResultChannel
    )
}
