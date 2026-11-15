package dev.rinstel.genie_tts.inference

import android.util.Log
import java.math.BigDecimal

enum class InferenceModelRole(val wireValue: String) {
    TEXT_FRONTEND("frontend"),
    ROBERTA("roberta"),
    HUBERT("hubert"),
    SPEAKER_ENCODER("speaker_encoder"),
    PROMPT_ENCODER("prompt_encoder"),
    T2S("t2s"),
    VOCODER("vocoder"),
}

enum class InferenceProvider(val wireValue: String) {
    CPU("cpu"),
    WEBGPU("webgpu"),
}

enum class InferenceCacheStatus(val wireValue: String) {
    HIT("hit"),
    MISS("miss"),
    NOT_APPLICABLE("n/a"),
}

/** Metadata-only record for comparing inference boundaries across runtimes. */
data class InferenceTraceRecord(
    val modelRole: InferenceModelRole,
    val provider: InferenceProvider,
    val tensorShape: LongArray? = null,
    val cacheStatus: InferenceCacheStatus = InferenceCacheStatus.NOT_APPLICABLE,
    val elapsedMs: Long? = null,
) {

    fun toLogLine(): String = buildList {
        add("role=${modelRole.wireValue}")
        add("provider=${provider.wireValue}")
        tensorShape?.let { add("shape=[${it.joinToString(",")}]") }
        add("cache=${cacheStatus.wireValue}")
        elapsedMs?.let { add("elapsed_ms=$it") }
    }.joinToString(" ")
}

interface InferenceTraceLogger {
    val tensorDiagnostics: Boolean get() = false
    fun stage(name: String, elapsedMs: Long)
    fun event(event: InferenceTraceEvent)

    object None : InferenceTraceLogger {
        override fun stage(name: String, elapsedMs: Long) = Unit
        override fun event(event: InferenceTraceEvent) = Unit
    }
}

enum class InferenceRuntimeLabel(val wireValue: String) {
    CPU("ORT CPU EP"),
    WEBGPU("ORT CPU + WebGPU"),
}

interface InferenceTensorMetricName {
    val wireValue: String
}

data class NamedInferenceTensorMetric(
    override val wireValue: String,
) : InferenceTensorMetricName {
}

enum class InferenceTensorMetric(override val wireValue: String) : InferenceTensorMetricName {
    AUDIO_SAMPLES("audio_samples"),
    AUDIO_SHAPE("audio_shape"),
    AUDIO_HASH("audio_hash"),
    TEXT_SEQ_HASH("text_seq_hash"),
    TEXT_BERT_HASH("text_bert_hash"),
    REF_SEQ_HASH("ref_seq_hash"),
    REF_BERT_HASH("ref_bert_hash"),
    REFERENCE_AUDIO_HASH("reference_audio_hash"),
    REFERENCE_AUDIO_16K_HASH("reference_audio_16k_hash"),
    SSL_CONTENT_HASH("ssl_content_hash"),
    SPEAKER_EMBEDDING_HASH("speaker_embedding_hash"),
    GLOBAL_EMBEDDING_HASH("global_embedding_hash"),
    ADVANCED_GLOBAL_EMBEDDING_HASH("advanced_global_embedding_hash"),
    SEMANTIC_TOKENS("semantic_tokens"),
    SEMANTIC_SHAPE("semantic_shape"),
    SEMANTIC_HASH("semantic_hash"),
}

sealed interface InferenceTraceEvent {
    fun toLogLine(): String

    data class DecoderAttempt(val attempt: Int, val semanticTokens: Int?, val elapsedMs: Long, val outcome: String = "ok") : InferenceTraceEvent {
        override fun toLogLine(): String =
            "decoder_attempt: {\"attempt\":$attempt,\"semantic_tokens\":$semanticTokens,\"elapsed_ms\":$elapsedMs,\"outcome\":\"$outcome\"}"
    }

    data class Backend(val value: ExecutionBackend) : InferenceTraceEvent {
        override fun toLogLine(): String = "resolved_backend: ${value.name}"
    }

    data class Runtime(val value: InferenceRuntimeLabel) : InferenceTraceEvent {
        override fun toLogLine(): String = "runtime_label: ${value.wireValue}"
    }

    data class TensorCount(val metric: InferenceTensorMetric, val value: Long) : InferenceTraceEvent {
        override fun toLogLine(): String = "${metric.wireValue}: $value"
    }

    data class TensorShape(val metric: InferenceTensorMetric, val value: LongArray) : InferenceTraceEvent {
        override fun toLogLine(): String = "${metric.wireValue}: ${value.contentToString()}"
    }

    data class TensorHash(val metric: InferenceTensorMetricName, val value: String) : InferenceTraceEvent {

        override fun toLogLine(): String = "${metric.wireValue}: $value"
    }

    data class TensorStatistics(
        val metric: InferenceTensorMetricName,
        val finiteCount: Long,
        val nonFiniteCount: Long,
        val min: Float?,
        val max: Float?,
        val mean: Double?,
    ) : InferenceTraceEvent {

        override fun toLogLine(): String {
            val name = metric.wireValue.removeSuffix("_hash")
            return "${name}_stats: finite_count=$finiteCount " +
                "non_finite_count=$nonFiniteCount " +
                "min=${formatStatistic(min?.toDouble())} " +
                "max=${formatStatistic(max?.toDouble())} " +
                "mean=${formatStatistic(mean)}"
        }

        private fun formatStatistic(value: Double?): String = when {
            value == null -> "null"
            value == 0.0 -> "0"
            else -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        }
    }

    data class Cache(val status: InferenceCacheStatus) : InferenceTraceEvent {
        override fun toLogLine(): String = "reference_cache: ${status.wireValue}"
    }

    data class SessionCache(
        val role: InferenceModelRole,
        val retained: Boolean,
        val totalMemoryMiB: Long,
        val availableMemoryMiB: Long,
    ) : InferenceTraceEvent {

        override fun toLogLine(): String =
            "session_cache: role=${role.wireValue} retained=$retained " +
                "total_mem_mib=$totalMemoryMiB available_mem_mib=$availableMemoryMiB"
    }

    data class Model(val value: InferenceTraceRecord) : InferenceTraceEvent {
        override fun toLogLine(): String = value.toLogLine()
    }
}

class LogcatInferenceTraceLogger(
    private val tag: String = TAG,
) : InferenceTraceLogger {
    // Enable tensor diagnostics explicitly with: adb shell setprop log.tag.GenieTtsTiming DEBUG
    override val tensorDiagnostics: Boolean get() = Log.isLoggable(tag, Log.DEBUG)

    override fun stage(name: String, elapsedMs: Long) {
        Log.i(tag, "$name=$elapsedMs ms")
    }

    override fun event(event: InferenceTraceEvent) {
        Log.i(tag, event.toLogLine())
    }

    companion object {
        private const val TAG = "GenieTtsTiming"
    }
}

class InferenceTimer(
    private val logger: InferenceTraceLogger,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    val tensorDiagnostics: Boolean = logger.tensorDiagnostics

    fun <T> measure(name: String, block: () -> T): T {
        if (logger === InferenceTraceLogger.None) return block()
        val start = clockNanos()
        try {
            return block()
        } finally {
            logger.stage(name, nanosToMillis(clockNanos() - start))
        }
    }

    fun event(event: InferenceTraceEvent) {
        logger.event(event)
    }

    private fun nanosToMillis(nanos: Long): Long = nanos / NANOS_PER_MILLISECOND

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

fun InferenceTimer.traceFloatTensor(
    metric: InferenceTensorMetricName,
    values: FloatArray,
) {
    if (!tensorDiagnostics) return
    val summary = InferenceTraceHash.floatSummary(values)
    event(InferenceTraceEvent.TensorHash(metric, summary.hash))
    event(
        InferenceTraceEvent.TensorStatistics(
            metric = metric,
            finiteCount = summary.finiteCount,
            nonFiniteCount = summary.nonFiniteCount,
            min = summary.min,
            max = summary.max,
            mean = summary.mean,
        ),
    )
}

fun InferenceTimer.traceLongTensor(metric: InferenceTensorMetricName, values: LongArray) {
    if (tensorDiagnostics) event(InferenceTraceEvent.TensorHash(metric, InferenceTraceHash.sha256(values)))
}
