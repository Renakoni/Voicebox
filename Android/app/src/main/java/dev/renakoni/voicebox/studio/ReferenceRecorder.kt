package dev.renakoni.voicebox.studio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/** Owns only the in-progress recording. A completed file is handed to the voice draft. */
class ReferenceRecorder(private val context: Context, private val directory: File,
    private val onTime: (Long) -> Unit, private val onLimit: () -> Unit, private val onError: () -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (recorder == null) return
            onTime(SystemClock.elapsedRealtime() - startedAt)
            handler.postDelayed(this, 200)
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        check(recorder == null)
        val target = File.createTempFile("draft-recording-", ".m4a", directory)
        file = target
        try {
            val current = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = current
            current.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setMaxDuration(30_000)
                setOutputFile(target.absolutePath)
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit() }
                setOnErrorListener { _, _, _ -> onError() }
                prepare()
                start()
            }
            startedAt = SystemClock.elapsedRealtime()
            handler.post(ticker)
        } catch (error: Exception) {
            cancel()
            throw error
        }
    }

    fun finish(): File {
        val current = checkNotNull(recorder)
        val target = checkNotNull(file)
        handler.removeCallbacks(ticker)
        try {
            current.stop()
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            current.release()
            recorder = null
            file = null
        }
    }

    fun cancel() {
        handler.removeCallbacks(ticker)
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
    }

    override fun close() = cancel()
}
