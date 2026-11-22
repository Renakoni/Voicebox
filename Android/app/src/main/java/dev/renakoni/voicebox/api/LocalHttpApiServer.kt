package dev.renakoni.voicebox.api

import dev.renakoni.voicebox.BackendServiceState
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ExecutionBackend
import dev.renakoni.voicebox.bridge.GenerationRequest
import dev.renakoni.voicebox.core.ReferenceLanguage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LocalHttpApiServer(
    private val bridge: Bridge,
) {
    interface Bridge {
        fun currentState(): BackendServiceState
        fun availableModels(): List<CharacterModel>
        fun referenceExample(model: CharacterModel): dev.renakoni.voicebox.bridge.ReferenceExample?
        fun supportsAuxiliaryReferences(model: CharacterModel): Boolean =
            model.maxAuxiliaryReferences > 0
        fun resolveSynthesisOptions(model: CharacterModel, defaults: Map<String, String>, overrides: Map<String, String>): Map<String, String>
        fun usesReference(model: CharacterModel, options: Map<String, String>): Boolean = true
        fun requiresOutputLanguage(model: CharacterModel, options: Map<String, String>): Boolean = true
        fun activeDefaults(): ActiveDefaults?
        fun inferBlocking(backend: ExecutionBackend, request: GenerationRequest, timeoutMs: Long): InferResult
        fun cancel(requestId: String): Boolean = false
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    private val requestExecutor: ExecutorService = ThreadPoolExecutor(0, 4, 60L, TimeUnit.SECONDS, SynchronousQueue())

    fun start(port: Int) {
        if (serverSocket?.let { !it.isClosed && it.localPort == port } == true) return
        stop()
        if (port !in MIN_API_PORT..MAX_API_PORT) {
            updateStatus(
                ApiServerStatus(
                    running = false,
                    port = port,
                    lastError = "API port must be between $MIN_API_PORT and $MAX_API_PORT.",
                ),
            )
            return
        }
        try {
            val socket = ServerSocket()
            serverSocket = socket
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), port))
            updateStatus(ApiServerStatus(running = true, port = port))
            acceptThread = thread(
                start = true,
                isDaemon = true,
                name = "VoiceBoxLocalHttpApi",
            ) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    try {
                        requestExecutor.execute { runCatching { handleClient(client) } }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        client.close()
                    }
                }
            }
        } catch (error: Throwable) {
            runCatching { serverSocket?.close() }
            serverSocket = null
            updateStatus(
                ApiServerStatus(
                    running = false,
                    port = port,
                    lastError = error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        serverSocket = null
        val previousPort = status.port
        updateStatus(ApiServerStatus(running = false, port = previousPort))
    }

    fun close() {
        stop()
        requestExecutor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10_000
            val request = runCatching { readRequest(client) }.getOrElse { error ->
                writeResponse(client, HttpResponse(400, errorJson(error.message ?: "Bad request.")))
                return
            }
            val response = route(
                method = request.method,
                path = request.path,
                body = request.body,
                queryParams = request.queryParams,
            )
            writeResponse(client, response)
        }
    }

    internal fun route(method: String, path: String, body: String = "", queryParams: Map<String, String> = emptyMap()): HttpResponse =
        when {
            method == "GET" && path == "/health" -> HttpResponse(
                200,
                jsonObject(
                    "ok" to true,
                    "address" to LOOPBACK_ADDRESS,
                    "port" to status.port,
                ),
            )

            method == "GET" && path == "/status" -> HttpResponse(
                200,
                jsonObject(
                    "ok" to true,
                    "state" to RawJson(bridge.currentState().toJsonString()),
                ),
            )

            method == "GET" && path == "/models" -> HttpResponse(
                200,
                jsonObject(
                    "ok" to true,
                    "models" to RawJson(bridge.availableModels().toJsonString()),
                ),
            )

            method == "GET" && path == "/result/latest" -> HttpResponse(
                200,
                jsonObject(
                    "ok" to true,
                    "latestOutputFilePath" to bridge.currentState().latestOutputFilePath,
                ),
            )

            method == "GET" && path == "/infer" -> inferGet(queryParams)
            method == "POST" && path == "/infer" -> infer(body)
            method == "POST" && path == "/cancel" -> {
                val requestId = queryParams["requestId"]
                when {
                    requestId.isNullOrBlank() -> HttpResponse(400, errorJson("requestId is required."))
                    bridge.cancel(requestId) -> HttpResponse(200, jsonObject("ok" to true, "requestId" to requestId))
                    else -> HttpResponse(404, errorJson("No active request with this requestId."))
                }
            }

            else -> HttpResponse(404, errorJson("Route not found."))
        }

    private fun infer(body: String): HttpResponse {
        val payload = runCatching { parseInferPayload(body) }.getOrElse {
            return HttpResponse(400, errorJson(it.message ?: "Invalid JSON body."))
        }
        return inferWithPayload(payload)
    }

    private fun inferGet(params: Map<String, String>): HttpResponse {
        val defaults = bridge.activeDefaults()
        val explicitModel = params.keys.any { it == "modelId" || it == "variant" || it == "backendId" }
        val payload = InferPayload(
            options = requestOptions(params),
            backendId = if (explicitModel) params["backendId"] else defaults?.model?.backendId,
            variant = if (explicitModel) params["variant"] else defaults?.model?.variant,
            modelId = params["modelId"] ?: defaults?.model?.modelId.orEmpty(),
            backendName = params["backend"].orEmpty(),
            language = params["language"].orEmpty(),
            promptLanguage = params["promptLanguage"].orEmpty(),
            text = params["text"].orEmpty(),
            referenceAudioPath = params["referenceAudioPath"].orEmpty(),
            referenceText = params["referenceText"].orEmpty(),
            auxiliaryReferenceAudioPaths = params["auxReferenceAudioPaths"]?.split('|')?.map(String::trim)?.filter(String::isNotBlank).orEmpty(),
        )
        return inferWithPayload(payload, defaults, useVoiceDefaults = true, useDefaultAuxiliary = "auxReferenceAudioPaths" !in params)
    }

    private fun inferWithPayload(input: InferPayload, defaults: ActiveDefaults? = bridge.activeDefaults(),
        useVoiceDefaults: Boolean = false, useDefaultAuxiliary: Boolean = false): HttpResponse {
        val matches = bridge.availableModels().filter {
            it.id == input.modelId && (input.backendId == null || it.ref.backendId == input.backendId) &&
                (input.variant == null || it.ref.variant == input.variant)
        }
        if (matches.isEmpty()) return HttpResponse(400, errorJson("Unknown modelId."))
        val model = matches.singleOrNull() ?: return HttpResponse(400, errorJson("Specify backendId and variant for this modelId."))
        val active = defaults?.takeIf { it.model == model.ref }
        val options = try { bridge.resolveSynthesisOptions(model, active?.options.orEmpty(), input.options) }
        catch (error: IllegalArgumentException) { return HttpResponse(400, errorJson(error.message ?: "Invalid options.")) }
        val requiresLanguage = bridge.requiresOutputLanguage(model, options)
        val usesReference = bridge.usesReference(model, options)
        val payload = if (useVoiceDefaults) {
            val reference = if (usesReference && active == null && (input.referenceAudioPath.isBlank() || input.referenceText.isBlank()))
                runCatching { bridge.referenceExample(model) }.getOrNull() else null
            val referenceLanguage = reference?.let {
                if (it.language == "auto") ReferenceLanguage.resolve(it.language, it.referenceText.orEmpty()) else it.language
            }
            val language = input.language.ifBlank { active?.language ?: referenceLanguage.orEmpty().takeIf { requiresLanguage }.orEmpty() }
            input.copy(
                backendName = input.backendName.ifBlank { active?.backend?.name.orEmpty() },
                language = language,
                promptLanguage = input.promptLanguage.ifBlank { active?.promptLanguage ?: referenceLanguage ?: language },
                referenceAudioPath = input.referenceAudioPath.ifBlank { active?.referenceAudioPath ?: reference?.audioFile?.absolutePath.orEmpty() },
                referenceText = input.referenceText.ifBlank { active?.referenceText ?: reference?.referenceText.orEmpty() },
                auxiliaryReferenceAudioPaths = if (useDefaultAuxiliary) active?.auxiliaryReferenceAudioPaths.orEmpty() else input.auxiliaryReferenceAudioPaths,
            )
        } else input
        val backend = ExecutionBackend.entries.firstOrNull { it.name.equals(payload.backendName.ifBlank { "CPU" }, ignoreCase = true) }
            ?: return HttpResponse(400, errorJson("Unknown backend."))
        val required = buildList {
            if (requiresLanguage && payload.language.isBlank()) add("language")
            if (payload.text.isBlank()) add("text")
            if (usesReference && payload.referenceAudioPath.isBlank()) add("referenceAudioPath")
            if (usesReference && payload.referenceText.isBlank()) add("referenceText")
        }
        if (required.isNotEmpty()) return HttpResponse(400, errorJson("Missing required fields: ${required.joinToString()}"))
        if (usesReference && !File(payload.referenceAudioPath).isFile) {
            return HttpResponse(400, errorJson("Reference audio not found: ${payload.referenceAudioPath}"))
        }
        validateAuxiliaryReferenceCapability(model, payload.auxiliaryReferenceAudioPaths)?.let { error ->
            return HttpResponse(400, errorJson(error))
        }
        validateAuxiliaryReferencePaths(payload.auxiliaryReferenceAudioPaths, payload.referenceAudioPath)?.let { error ->
            return HttpResponse(400, errorJson(error))
        }

        val result = bridge.inferBlocking(
            backend = backend,
            request = GenerationRequest(
                characterModel = model,
                language = payload.language,
                promptLanguage = payload.promptLanguage.takeIf { usesReference }.orEmpty(),
                synthesisText = payload.text,
                referenceAudioPath = payload.referenceAudioPath.takeIf { usesReference }.orEmpty(),
                referenceText = payload.referenceText.takeIf { usesReference }.orEmpty(),
                auxiliaryReferenceAudioPaths = payload.auxiliaryReferenceAudioPaths,
                options = options,
            ),
            timeoutMs = INFER_TIMEOUT_MS,
        )
        return when (result) {
            is InferResult.Success -> HttpResponse(200, "", "audio/wav", result.outputFile)
            is InferResult.Error -> HttpResponse(500, errorJson(result.message, result.code))
            InferResult.BackendUnavailable -> HttpResponse(400, errorJson("Backend is not enabled."))
            InferResult.Busy -> HttpResponse(409, errorJson("Inference is already running."))
            InferResult.Timeout -> HttpResponse(504, errorJson("Inference timed out.", "TIMEOUT"))
        }
    }

    private fun validateAuxiliaryReferencePaths(
        paths: List<String>,
        primaryPath: String,
    ): String? {
        if (paths.isEmpty()) return null
        fun canonicalFile(path: String) = File(path).let { runCatching { it.canonicalFile }.getOrDefault(it.absoluteFile) }
        val primary = canonicalFile(primaryPath)
        val seen = mutableSetOf<File>()
        for (rawPath in paths) {
            val canonical = canonicalFile(rawPath)
            if (!canonical.isFile) {
                return "Auxiliary reference audio not found: $rawPath"
            }
            if (canonical == primary) {
                return "Auxiliary reference path duplicates the primary reference."
            }
            if (!seen.add(canonical)) return "Auxiliary reference paths must be unique."
        }
        return null
    }

    private fun validateAuxiliaryReferenceCapability(
        model: CharacterModel,
        paths: List<String>,
    ): String? {
        if (paths.isEmpty()) return null
        if (model.maxAuxiliaryReferences <= 0) {
            return "The selected model does not support auxiliary references."
        }
        if (!bridge.supportsAuxiliaryReferences(model)) {
            return "The selected model does not have the multi-reference prompt encoder installed."
        }
        if (paths.size > model.maxAuxiliaryReferences) {
            return "At most ${model.maxAuxiliaryReferences} auxiliary references are supported."
        }
        return null
    }

    private fun readRequest(socket: Socket): ParsedRequest {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readAsciiLine(input)
        val parts = requestLine.split(" ")
        require(parts.size >= 2) { "Malformed request line." }
        val method = parts[0].uppercase()
        val rawPath = parts[1]
        val path = rawPath.substringBefore('?')
        val queryString = rawPath.substringAfter('?', "")
        val queryParams = parseQueryParams(queryString)
        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readAsciiLine(input)
            if (line.isBlank()) {
                break
            }
            headerBytes += line.length
            require(headerBytes <= 65_536) { "Request headers are too large." }
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.let {
            it.toIntOrNull() ?: error("Invalid Content-Length.")
        } ?: 0
        require(contentLength in 0..MAX_REQUEST_BYTES) { "Request body must be at most $MAX_REQUEST_BYTES bytes." }
        val bodyBytes = ByteArray(contentLength).also { DataInputStream(input).readFully(it) }
        return ParsedRequest(
            method = method,
            path = path,
            body = String(bodyBytes, StandardCharsets.UTF_8),
            queryParams = queryParams,
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val key = java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                    val value = java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                    key to value
                } else if (pair.isNotBlank()) {
                    java.net.URLDecoder.decode(pair, StandardCharsets.UTF_8.name()) to ""
                } else {
                    null
                }
            }
            .toMap()
    }

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val input = try { response.audioFile?.inputStream() } catch (error: java.io.IOException) {
            return writeResponse(socket, HttpResponse(500, errorJson("Failed to open output: ${error.message}")))
        }
        input.use { audio ->
            val output = BufferedOutputStream(socket.getOutputStream())
            val bodyBytes = response.body.toByteArray(StandardCharsets.UTF_8)
            val statusText = when (response.statusCode) {
                200 -> "OK"
                400 -> "Bad Request"
                404 -> "Not Found"
                409 -> "Conflict"
                500 -> "Internal Server Error"
                504 -> "Gateway Timeout"
                else -> "Error"
            }
            val header = buildString {
                append("HTTP/1.1 ${response.statusCode} $statusText\r\n")
                append("Content-Type: ${response.contentType}\r\n")
                if (audio != null) {
                    append("Content-Disposition: inline; filename=\"tts_output.wav\"\r\n")
                }
                append("Content-Length: ${audio?.channel?.size() ?: bodyBytes.size.toLong()}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(header.toByteArray(StandardCharsets.UTF_8))
            if (audio == null) output.write(bodyBytes) else audio.copyTo(output)
            output.flush()
        }
    }

    private fun readAsciiLine(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            require(next >= 0) { "Unexpected end of stream." }
            if (next == '\n'.code) {
                break
            }
            if (next != '\r'.code) {
                require(bytes.size() < MAX_LINE_BYTES) { "Request line is too long." }
                bytes.write(next)
            }
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun errorJson(message: String, code: String? = null): String =
        jsonObject(
            "ok" to false,
            "error" to message,
            "errorCode" to code,
        )

    private data class ParsedRequest(
        val method: String,
        val path: String,
        val body: String,
        val queryParams: Map<String, String>,
    )

    companion object {
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val MIN_API_PORT = 1024
        private const val MAX_API_PORT = 65535
        // The first V5 request can spend several minutes compiling its NPU contexts.
        private const val INFER_TIMEOUT_MS = 600_000L
        private const val MAX_REQUEST_BYTES = 1024 * 1024
        private const val MAX_LINE_BYTES = 8192

        @Volatile
        private var status: ApiServerStatus = ApiServerStatus(running = false, port = 0)

        fun currentStatus(): ApiServerStatus = status

        private fun updateStatus(value: ApiServerStatus) {
            status = value
        }
    }
}
