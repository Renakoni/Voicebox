package dev.renakoni.voicebox.studio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class StreamPlayback(val elapsedMs: Long = 0, val paused: Boolean = false, val playing: Boolean = false)

internal class StreamingPlayback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val update: (StreamPlayback?) -> Unit,
    private val onError: (Exception) -> Unit,
) : AutoCloseable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ if (it < 0) pause() }, Handler(Looper.getMainLooper())).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = pause()
    }
    private var directory: File? = null
    private var job: Job? = null
    private var track: AudioTrack? = null
    private var state = StreamPlayback()
    private var complete = false

    init {
        ContextCompat.registerReceiver(context, noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun start(target: File, sampleRate: Int) {
        check(directory == null && target.mkdirs())
        directory = target
        complete = false
        state = StreamPlayback()
        update(state)
        job = scope.launch {
            try {
                play(target, sampleRate)
                stop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error)
                stop()
            }
        }
    }

    fun finishSynthesis() { complete = true }

    fun togglePause() {
        if (directory == null) return
        if (state.paused) publish(state.copy(paused = false)) else pause()
    }

    private fun pause() {
        if (directory == null || state.paused) return
        track?.pause()
        audioManager.abandonAudioFocusRequest(focus)
        publish(state.copy(paused = true, playing = false))
    }

    private suspend fun play(directory: File, sampleRate: Int) {
        var next = 0
        var acknowledged = 0
        var written = 0L
        var clip: ByteBuffer? = null
        val clipEnds = ArrayDeque<Long>()
        while (true) {
            var player = track
            val played = player?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L
            val firstAcknowledgement = acknowledged
            while (clipEnds.firstOrNull()?.let { played >= it } == true) {
                acknowledged++
                clipEnds.removeFirst()
            }
            if (acknowledged > firstAcknowledgement) withContext(Dispatchers.IO) {
                for (index in firstAcknowledgement until acknowledged) {
                    File(directory, "played-$index").createNewFile()
                }
            }
            val drained = complete && clipEnds.isEmpty()
            if (clip == null && !state.paused) {
                val bytes = withContext(Dispatchers.IO) {
                    File(directory, "$next.pcm").takeIf(File::isFile)?.readBytes()
                }
                if (bytes == null && drained) return
                clip = bytes?.let(ByteBuffer::wrap)
            } else if (clip == null && drained && withContext(Dispatchers.IO) { !File(directory, "$next.pcm").isFile }) {
                return
            }
            val buffer = clip
            if (!state.paused && (buffer != null || played < written)) {
                if (player == null) player = openTrack(sampleRate).also { track = it }
                if (player.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    if (audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) player.play()
                    else pause()
                }
                if (!state.paused && buffer != null) {
                    val count = player.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                    check(count >= 0 && count % 2 == 0) { "AudioTrack write failed ($count)" }
                    written += count / 2
                    if (!buffer.hasRemaining()) {
                        clipEnds.addLast(written)
                        next++
                        clip = null
                    }
                }
            }
            publish(state.copy(elapsedMs = played * 1000 / sampleRate, playing = !state.paused && played < written))
            delay(20)
        }
    }

    private fun openTrack(sampleRate: Int): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "PCM playback is unavailable" }
        return AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
            .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, sampleRate * 2 / 5)).build()
    }

    private fun publish(next: StreamPlayback) {
        if (next == state) return
        state = next
        update(next)
    }

    fun stop() {
        release()?.let { previous -> scope.launch(Dispatchers.IO) { previous.deleteRecursively() } }
    }

    private fun release(): File? {
        val previous = directory ?: return null
        directory = null
        job?.cancel()
        job = null
        track?.release()
        track = null
        audioManager.abandonAudioFocusRequest(focus)
        update(null)
        return previous
    }

    override fun close() {
        release()?.deleteRecursively()
        context.unregisterReceiver(noisy)
    }

}
