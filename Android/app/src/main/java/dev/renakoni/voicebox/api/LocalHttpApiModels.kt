package dev.renakoni.voicebox.api

import dev.renakoni.voicebox.BackendServiceState
import dev.renakoni.voicebox.bridge.ModelRef
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ExecutionBackend
import java.io.File

data class ApiServerStatus(
    val running: Boolean,
    val port: Int,
    val address: String = "127.0.0.1",
    val lastError: String? = null,
)

data class ActiveDefaults(
    val backend: ExecutionBackend,
    val model: ModelRef,
    val language: String,
    val referenceAudioPath: String,
    val referenceText: String,
    val options: Map<String, String> = emptyMap(),
    val promptLanguage: String = language,
    val auxiliaryReferenceAudioPaths: List<String> = emptyList(),
)

sealed class InferResult {
    data class Success(val outputFile: File, val audioDurationMs: Long) : InferResult()
    data class Error(val message: String, val code: String = "INFERENCE_FAILED") : InferResult()
    data object Busy : InferResult()
    data object Timeout : InferResult()
}

internal data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val contentType: String = "application/json; charset=utf-8",
    val audioFile: File? = null,
)

internal data class InferPayload(
    val backendId: String? = null,
    val variant: String? = null,
    val backendName: String,
    val modelId: String,
    val language: String,
    val promptLanguage: String = language,
    val text: String,
    val referenceAudioPath: String,
    val referenceText: String,
    val options: Map<String, String> = emptyMap(),
    val auxiliaryReferenceAudioPaths: List<String> = emptyList(),
)

internal fun BackendServiceState.toJsonString(): String =
    jsonObject(
        "requestId" to requestId,
        "generationEpoch" to generationEpoch,
        "errorCode" to errorCode,
        "stage" to stage.name,
        "message" to message,
        "busy" to busy,
        "requestedBackend" to requestedBackend.name,
        "resolvedBackend" to resolvedBackend.name,
        "runtimeLabel" to runtimeLabel,
        "initializedModelId" to initializedModelId,
        "latestOutputFilePath" to latestOutputFilePath,
        "progressPercent" to progressPercent,
        "progressLabel" to progressLabel,
        "generationDurationMs" to generationDurationMs,
        "lastCompletedAtMs" to lastCompletedAtMs,
    )

internal fun List<CharacterModel>.toJsonString(): String =
    jsonArray(
        map { model ->
            jsonObject(
                "id" to model.id,
                "backendId" to model.ref.backendId,
                "variant" to model.ref.variant,
                "displayName" to model.displayName,
                "relativeModelDirectory" to model.relativeModelDirectory,
            )
        },
    )

private val commonFields = setOf("backend", "backendId", "variant", "modelId", "language", "promptLanguage", "text",
    "referenceAudioPath", "referenceText", "auxReferenceAudioPaths")

internal fun requestOptions(values: Map<String, *>): Map<String, String> =
    values.filterKeys { it !in commonFields }.mapValues { (name, value) ->
        require(value is String) { "$name must be a scalar." }
        value
    }

internal fun parseInferPayload(body: String): InferPayload {
    val parsed = parseJsonObject(body.ifBlank { "{}" })
    val values = parsed.filterKeys { it in commonFields && it != "auxReferenceAudioPaths" }.mapValues { (key, value) ->
        require(value is String) { "$key must be a scalar." }
        value
    }
    return InferPayload(
        backendId = values["backendId"],
        variant = values["variant"],
        backendName = values["backend"].orEmpty(),
        modelId = values["modelId"].orEmpty().trim(),
        language = values["language"].orEmpty().trim(),
        promptLanguage = values["promptLanguage"]?.trim().takeUnless { it.isNullOrEmpty() }
            ?: values["language"].orEmpty().trim(),
        text = values["text"].orEmpty().trim(),
        referenceAudioPath = values["referenceAudioPath"].orEmpty().trim(),
        referenceText = values["referenceText"].orEmpty().trim(),
        options = requestOptions(parsed),
        auxiliaryReferenceAudioPaths = parseReferencePathList(parsed["auxReferenceAudioPaths"]),
    )
}

internal fun jsonObject(vararg entries: Pair<String, Any?>): String =
    entries.joinToString(
        prefix = "{",
        postfix = "}",
        separator = ",",
    ) { (key, value) ->
        "${quoteJson(key)}:${jsonValue(value)}"
    }

internal fun jsonArray(items: List<String>): String =
    items.joinToString(prefix = "[", postfix = "]", separator = ",")

private fun parseReferencePathList(value: Any?): List<String> =
    when (value) {
        is List<*> -> value.map { it as String }
        null -> emptyList()
        else -> throw IllegalArgumentException("auxReferenceAudioPaths must be an array of paths.")
    }.map(String::trim).filter(String::isNotBlank)

private fun jsonValue(value: Any?): String =
    when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        is RawJson -> value.value
        else -> quoteJson(value.toString())
    }

private fun quoteJson(value: String): String {
    val escaped = buildString(value.length + 8) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u%04x".format(char.code))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
    return "\"$escaped\""
}

// The localhost API only needs a tiny flat-object parser, so keep it local and dependency-free.
private fun parseJsonObject(source: String): Map<String, Any?> {
    val cursor = JsonCursor(source)
    val result = linkedMapOf<String, Any?>()
    cursor.skipWhitespace()
    cursor.expect('{')
    cursor.skipWhitespace()
    if (cursor.peek() == '}') {
        cursor.expect('}')
        return result.also { cursor.expectEnd() }
    }
    while (true) {
        cursor.skipWhitespace()
        val key = cursor.readString()
        cursor.skipWhitespace()
        cursor.expect(':')
        cursor.skipWhitespace()
        result[key] = cursor.readPrimitiveOrString()
        cursor.skipWhitespace()
        when (cursor.readDelimiter()) {
            ',' -> continue
            '}' -> return result.also { cursor.expectEnd() }
            else -> throw IllegalArgumentException("Malformed JSON object.")
        }
    }
}

private class JsonCursor(
    private val source: String,
) {
    private var index = 0

    fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) {
            index += 1
        }
    }

    fun peek(): Char? = source.getOrNull(index)

    fun expectEnd() {
        skipWhitespace()
        require(index == source.length) { "Unexpected content after JSON object." }
    }

    fun expect(expected: Char) {
        val actual = source.getOrNull(index)
        require(actual == expected) { "Expected '$expected'." }
        index += 1
    }

    fun readDelimiter(): Char {
        val value = source.getOrNull(index) ?: throw IllegalArgumentException("Unexpected end of JSON.")
        index += 1
        return value
    }

    fun readString(): String {
        expect('"')
        val output = StringBuilder()
        while (true) {
            val current = source.getOrNull(index) ?: throw IllegalArgumentException("Unterminated JSON string.")
            index += 1
            when (current) {
                '"' -> return output.toString()
                '\\' -> output.append(readEscape())
                else -> {
                    require(current >= ' ') { "Unescaped control character in JSON string." }
                    output.append(current)
                }
            }
        }
    }

    fun readPrimitiveOrString(): Any? {
        if (peek() == '"') {
            return readString()
        }
        if (peek() == '[') {
            return readStringArray()
        }
        val start = index
        while (index < source.length) {
            val current = source[index]
            if (current == ',' || current == '}' || current.isWhitespace()) {
                break
            }
            index += 1
        }
        val token = source.substring(start, index).trim()
        if (token == "null") return null
        require(token == "true" || token == "false" || JSON_NUMBER.matches(token)) { "Invalid JSON value." }
        return token
    }

    private fun readStringArray(): List<String> {
        expect('[')
        skipWhitespace()
        if (peek() == ']') {
            expect(']')
            return emptyList()
        }
        val values = mutableListOf<String>()
        while (true) {
            skipWhitespace()
            values += readString()
            skipWhitespace()
            when (readDelimiter()) {
                ',' -> continue
                ']' -> return values
                else -> throw IllegalArgumentException("Malformed JSON array.")
            }
        }
    }

    private fun readEscape(): Char {
        val escaped = source.getOrNull(index) ?: throw IllegalArgumentException("Incomplete JSON escape.")
        index += 1
        return when (escaped) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val hex = source.substring(index, (index + 4).coerceAtMost(source.length))
                require(hex.length == 4) { "Incomplete unicode escape." }
                require(hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Invalid unicode escape." }
                index += 4
                hex.toInt(16).toChar()
            }
            else -> throw IllegalArgumentException("Unsupported JSON escape.")
        }
    }
}

private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

@JvmInline
internal value class RawJson(
    val value: String,
)
