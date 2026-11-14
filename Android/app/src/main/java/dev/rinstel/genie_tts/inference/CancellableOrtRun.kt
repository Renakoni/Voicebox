package dev.rinstel.genie_tts.inference

import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession

/** The owner closes RunOptions only after its cross-thread terminate hook is detached. */
fun OrtSession.runCancellable(inputs: Map<String, OnnxTensorLike>): OrtSession.Result {
    val context = GenerationContext.current()
    context?.checkCancelled()
    val options = OrtSession.RunOptions()
    var registration: AutoCloseable? = null
    try {
        options.setLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
        registration = context?.onCancel { options.setTerminate(true) }
        val result = run(inputs, options)
        try {
            context?.checkCancelled()
            return result
        } catch (error: Throwable) {
            result.close()
            throw error
        }
    } catch (error: Exception) {
        context?.checkCancelled()
        throw error
    } finally {
        registration?.close()
        options.close()
    }
}
