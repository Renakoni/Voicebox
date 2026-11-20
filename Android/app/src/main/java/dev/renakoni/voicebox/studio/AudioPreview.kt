package dev.renakoni.voicebox.studio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

data class PreviewState(val path: String = "", val playing: Boolean = false,
    val preparing: Boolean = false, val positionMs: Int = 0, val durationMs: Int = 0,
    val seeking: Boolean = false)

class AudioPreview(private val context: Context, private val update: (PreviewState) -> Unit,
    private val onError: () -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change < 0) stop()
        }.build()
    private var player: MediaPlayer? = null
    private var state = PreviewState()
    private val ticker = object : Runnable {
        override fun run() {
            player?.takeIf { state.playing }?.let {
                if (!state.seeking) publishPosition(it.currentPosition)
                handler.postDelayed(this, 16L)
            }
        }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = stop()
    }

    init {
        ContextCompat.registerReceiver(context, noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun toggle(path: String) {
        if (state.path == path && state.playing) {
            player?.pause()
            audioManager.abandonAudioFocusRequest(focus)
            handler.removeCallbacks(ticker)
            state = state.copy(playing = false)
            publishPosition(if (state.seeking) state.positionMs else player!!.currentPosition)
            return
        }
        if (state.path == path && state.preparing) return
        if (state.path == path && player != null) {
            startPrepared()
            return
        }
        stop()
        state = PreviewState(path = path, preparing = true)
        update(state)
        runCatching {
            val created = MediaPlayer()
            player = created
            created.apply {
                setAudioAttributes(attributes)
                setDataSource(path)
                setOnPreparedListener {
                    state = state.copy(preparing = false, durationMs = it.duration)
                    startPrepared()
                }
                setOnSeekCompleteListener {
                    state = state.copy(seeking = false)
                    publishPosition(it.currentPosition)
                }
                setOnCompletionListener {
                    handler.removeCallbacks(ticker)
                    audioManager.abandonAudioFocusRequest(focus)
                    state = state.copy(playing = false, seeking = false)
                    publishPosition(state.durationMs)
                }
                setOnErrorListener { _, _, _ -> stop(); onError(); true }
                prepareAsync()
            }
        }.onFailure { stop(); onError() }
    }

    private fun startPrepared() {
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stop()
            onError()
            return
        }
        val replay = state.positionMs >= state.durationMs
        val position = if (replay) 0 else state.positionMs
        player?.start()
        state = state.copy(playing = true)
        publishPosition(position)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun seek(positionMs: Int) {
        if (state.preparing) return
        val current = player ?: return
        val target = positionMs.coerceIn(0, state.durationMs)
        state = state.copy(seeking = true)
        publishPosition(target)
        current.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    private fun publishPosition(positionMs: Int) {
        state = state.copy(positionMs = positionMs)
        update(state)
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        player?.release()
        player = null
        audioManager.abandonAudioFocusRequest(focus)
        state = PreviewState()
        update(state)
    }

    override fun close() {
        stop()
        context.unregisterReceiver(noisy)
    }
}
