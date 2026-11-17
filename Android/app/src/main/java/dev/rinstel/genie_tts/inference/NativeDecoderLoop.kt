package dev.rinstel.genie_tts.inference

import ai.onnxruntime.OrtSession
import java.lang.reflect.Field

/** Native ORT decoder with shared KV buffers. */
object NativeDecoderLoop {
    private var loaded = false
    private var sessionHandleField: Field? = null

    fun interface DecoderProgressCallback {
        fun onProgress(generatedSteps: Int, maxSteps: Int)
    }

    init {
        try {
            System.loadLibrary("native_decoder_loop")
            loaded = true
        } catch (error: UnsatisfiedLinkError) {
            loaded = false
        }
    }

    fun isAvailable(): Boolean = loaded

    private fun getSessionNativeHandle(session: OrtSession): Long {
        val field = sessionHandleField ?: run {
            val declared = OrtSession::class.java.getDeclaredField("nativeHandle")
            declared.isAccessible = true
            sessionHandleField = declared
            declared
        }
        return field.getLong(session)
    }

    @JvmStatic
    private external fun nativeCreateCancellation(): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeDeleteCancellation(handle: Long)

    @JvmStatic
    external fun nativeDecoderLoop(
        sessionPtr: Long,
        inputNames: Array<String>,
        outputNames: Array<String>,
        yData: LongArray,
        yShape: LongArray,
        yEmbData: FloatArray,
        yEmbShape: LongArray,
        kvData: FloatArray,
        kvShape: LongArray,
        numKvTensors: Int,
        outputMapping: IntArray,
        sampling: FloatArray,
        maxSteps: Int,
        progressCallback: DecoderProgressCallback?,
        cancellationHandle: Long,
    ): LongArray?

    fun runDecoderLoop(
        session: OrtSession,
        inputNames: List<String>,
        outputNames: List<String>,
        initialY: LongArray,
        yShape: LongArray,
        initialYEmb: FloatArray,
        yEmbShape: LongArray,
        initialKv: FloatArray,
        kvShape: LongArray,
        numKvTensors: Int,
        outputMapping: IntArray,
        sampling: FloatArray,
        maxSteps: Int,
        progressCallback: DecoderProgressCallback?,
    ): LongArray {
        val sessionPtr = getSessionNativeHandle(session)
        val context = GenerationContext.current()
        context?.checkCancelled()
        val cancellationHandle = nativeCreateCancellation()
        check(cancellationHandle != 0L) { "Cannot create native cancellation context." }
        var registration: AutoCloseable? = null
        try {
            registration = context?.onCancel { nativeCancel(cancellationHandle) }
            val result = nativeDecoderLoop(
                sessionPtr,
                inputNames.toTypedArray(),
                outputNames.toTypedArray(),
                initialY,
                yShape,
                initialYEmb,
                yEmbShape,
                initialKv,
                kvShape,
                numKvTensors,
                outputMapping,
                sampling,
                maxSteps,
                progressCallback,
                cancellationHandle,
            )
            context?.checkCancelled()
            return requireNotNull(result) { "NATIVE_DECODER_FAILED: no semantic output." }
        } finally {
            registration?.close()
            nativeDeleteCancellation(cancellationHandle)
        }
    }
}
