package dev.renakoni.voicebox.api

import dev.renakoni.voicebox.BackendServiceState
import dev.renakoni.voicebox.bridge.ModelRef
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ExecutionBackend
import dev.renakoni.voicebox.bridge.GenerationRequest
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val testModel = CharacterModel(ModelRef("gptsovits", "v2ProPlus", "mansui"), "Test voice",
    "v2ProPlus/mansui/tts_models", maxAuxiliaryReferences = 8)

class LocalHttpApiServerTest {
    @Test
    fun sameModelIdRoutesByBackendAndDoesNotInheritAnotherBackendsOptions() {
        val gpt = testModel
        val ming = CharacterModel(ModelRef("ming", "dense-0.5b", gpt.id), "Ming", "ming/models")
        val capture = FakeBridge(models = listOf(gpt, ming),
            defaults = ActiveDefaults(ExecutionBackend.WEBGPU, gpt.ref, "zh", FakeBridge.primaryReferencePath,
                "sample", mapOf("speed" to "1.5"), auxiliaryReferenceAudioPaths = listOf("other-model-reference.wav")))
        val server = LocalHttpApiServer(object : LocalHttpApiServer.Bridge by capture {
            override fun validateOptions(model: CharacterModel, options: Map<String, String>): Map<String, String> {
                require(model.ref == ming.ref && options.isEmpty())
                return options
            }
        })
        val request = mapOf("modelId" to gpt.id, "text" to "hello")
        assertEquals(400, server.handleRequestForTest("GET", "/infer", queryParams = request).statusCode)
        val routed = request + ("backendId" to "ming")
        assertEquals(200, server.handleRequestForTest("GET", "/infer", queryParams = routed).statusCode)
        assertEquals(ming.ref, capture.capturedRequest?.characterModel?.ref)
        assertEquals(emptyList<String>(), capture.capturedRequest?.auxiliaryReferenceAudioPaths)
        assertEquals(ExecutionBackend.CPU, capture.capturedBackend)
        assertEquals(400, server.handleRequestForTest("GET", "/infer", queryParams = routed + ("speed" to "2")).statusCode)
    }

    @Test
    fun synthesisControlsUseActiveDefaultsAndRejectInvalidOverrides() {
        val defaults = ActiveDefaults(ExecutionBackend.CPU, ModelRef("gptsovits", "v2ProPlus", "mansui"), "zh", FakeBridge.primaryReferencePath,
            "sample", mapOf("speed" to "0.6", "temperature" to "0.7", "topK" to "30", "topP" to "0.9",
                "repetitionPenalty" to "1.35", "maxDecoderSteps" to "500"))
        val bridge = FakeBridge(defaults = defaults)
        val server = LocalHttpApiServer(bridge)
        val response = server.handleRequestForTest("GET", "/infer", queryParams = mapOf("text" to "test", "topK" to "1"))
        assertEquals(200, response.statusCode)
        assertEquals(defaults.options + ("topK" to "1"), bridge.capturedRequest?.options)
        val body = """{"modelId":"mansui","language":"zh","text":"test",
            "referenceAudioPath":"${FakeBridge.primaryReferenceJsonPath}","referenceText":"sample",
            "speed":1.65,"temperature":0,"topP":0,"repetitionPenalty":2}"""
        val captured = bridge.capturedRequest
        for (invalid in listOf(
            body.replace("\"speed\":1.65", "\"speed\":[\"1\"]"),
            body.replace("\"speed\":1.65", "\"speed\":1.65d"),
            "$body trailing",
        )) {
            assertEquals(400, server.handleRequestForTest("POST", "/infer", body = invalid).statusCode)
            assertEquals(captured, bridge.capturedRequest)
        }
        assertEquals(200, server.handleRequestForTest("POST", "/infer", body).statusCode)
        assertEquals(defaults.options + mapOf("speed" to "1.65", "temperature" to "0", "topP" to "0", "repetitionPenalty" to "2"),
            bridge.capturedRequest?.options)
        assertEquals(200, server.handleRequestForTest("GET", "/infer", queryParams = mapOf("text" to "next")).statusCode)
        assertEquals(defaults.options, bridge.capturedRequest?.options)
    }

    @Test
    fun startsOnConfiguredValidPort() {
        val probe = ServerSocket(0)
        val port = probe.localPort
        probe.close()
        val server = LocalHttpApiServer(FakeBridge())

        try {
            server.start(port)

            val status = LocalHttpApiServer.currentStatus()
            assertTrue(status.running)
            assertEquals(port, status.port)

            // Repeated service start commands must keep the same listener alive.
            val listener = LocalHttpApiServer::class.java.getDeclaredField("serverSocket").apply { isAccessible = true }
            val socket = listener.get(server)
            server.start(port)
            org.junit.Assert.assertSame(socket, listener.get(server))

            for (length in listOf("-1", "1048577", "invalid")) {
                Socket("127.0.0.1", port).use { client ->
                    client.soTimeout = 5000
                    client.getOutputStream().write("POST /infer HTTP/1.1\r\nContent-Length: $length\r\n\r\n".toByteArray())
                    assertTrue(client.getInputStream().bufferedReader().readLine().contains("400"))
                }
            }
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 5000
                client.getOutputStream().write("GET /health HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(client.getInputStream().bufferedReader().readLine().contains("200"))
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsPrivilegedPortBeforeAttemptingToBind() {
        val server = LocalHttpApiServer(FakeBridge())

        server.start(80)

        val status = LocalHttpApiServer.currentStatus()
        assertFalse(status.running)
        assertTrue(status.lastError?.contains("between 1024 and 65535") == true)
        server.stop()
    }

    @Test
    fun inferMapsTextFieldIntoGenerationRequest() {
        val bridge = FakeBridge()
        val server = LocalHttpApiServer(bridge)

        val response = server.handleRequestForTest(
            method = "POST",
            path = "/infer",
            body = """
                {
                  "backend": "CPU",
                  "modelId": "mansui",
                  "language": "zh",
                  "text": "hello",
                  "referenceAudioPath": "${FakeBridge.primaryReferenceJsonPath}",
                  "referenceText": "sample",
                  "maxDecoderSteps": 321
                }
            """.trimIndent(),
        )

        assertEquals(200, response.statusCode)
        assertEquals("audio/wav", response.contentType)
        assertTrue(response.binaryBody?.isNotEmpty() == true)
        assertEquals(ExecutionBackend.CPU, bridge.capturedBackend)
        assertEquals("hello", bridge.capturedRequest?.synthesisText)
        assertEquals("321", bridge.capturedRequest?.options?.get("maxDecoderSteps"))
    }

    @Test
    fun inferPreservesAuxiliaryReferenceOrder() {
        val bridge = FakeBridge()
        val server = LocalHttpApiServer(bridge)
        val first = File.createTempFile("aux-first", ".wav")
        val second = File.createTempFile("aux-second", ".wav")

        val response = server.handleRequestForTest(
            method = "POST",
            path = "/infer",
            body = """
                {
                  "backend": "CPU",
                  "modelId": "mansui",
                  "language": "zh",
                  "text": "hello",
                  "referenceAudioPath": "${FakeBridge.primaryReferenceJsonPath}",
                  "referenceText": "sample",
                  "auxReferenceAudioPaths": ["${first.absolutePath.replace("\\", "\\\\")}", "${second.absolutePath.replace("\\", "\\\\")}"]
                }
            """.trimIndent(),
        )

        assertEquals(200, response.statusCode)
        assertEquals(
            listOf(first.absolutePath, second.absolutePath),
            bridge.capturedRequest?.auxiliaryReferenceAudioPaths,
        )
        first.delete()
        second.delete()
    }

    @Test
    fun inferRejectsMissingAuxiliaryReferenceBeforeBackendCall() {
        val bridge = FakeBridge()
        val server = LocalHttpApiServer(bridge)

        val response = server.handleRequestForTest(
            method = "POST",
            path = "/infer",
            body = """
                {
                  "backend": "CPU",
                  "modelId": "mansui",
                  "language": "zh",
                  "text": "hello",
                  "referenceAudioPath": "${FakeBridge.primaryReferenceJsonPath}",
                  "referenceText": "sample",
                  "auxReferenceAudioPaths": ["/path/that/does/not/exist.wav"]
                }
            """.trimIndent(),
        )

        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("not found"))
        assertEquals(null, bridge.capturedRequest)
    }

    @Test
    fun inferRejectsAuxiliaryReferenceThatDuplicatesPrimaryBeforeBackendCall() {
        val bridge = FakeBridge()
        val server = LocalHttpApiServer(bridge)

        val response = server.handleRequestForTest(
            method = "POST",
            path = "/infer",
            body = """
                {
                  "backend": "CPU",
                  "modelId": "mansui",
                  "language": "zh",
                  "text": "hello",
                  "referenceAudioPath": "${FakeBridge.primaryReferenceJsonPath}",
                  "referenceText": "sample",
                  "auxReferenceAudioPaths": ["${FakeBridge.primaryReferenceJsonPath}"]
                }
            """.trimIndent(),
        )

        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("primary"))
        assertEquals(null, bridge.capturedRequest)
    }

    @Test
    fun inferRejectsMissingPrimaryReferenceBeforeBackendCall() {
        val bridge = FakeBridge()
        val server = LocalHttpApiServer(bridge)

        val response = server.handleRequestForTest(
            method = "POST",
            path = "/infer",
            body = """
                {
                  "backend": "CPU",
                  "modelId": "mansui",
                  "language": "zh",
                  "text": "hello",
                  "referenceAudioPath": "${FakeBridge.primaryReferenceJsonPath}.missing",
                  "referenceText": "sample"
                }
            """.trimIndent(),
        )

        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("Reference audio not found"))
        assertEquals(null, bridge.capturedRequest)
    }

    @Test
    fun inferReturnsBackendValidationErrorBeforeGeneration() {
        val bridge = FakeBridge(validate = { options ->
            assertEquals("0", options["maxDecoderSteps"])
            throw IllegalArgumentException("Rejected by model")
        })
        val server = LocalHttpApiServer(bridge)

        val response = server.handleRequestForTest(
            method = "POST",
            path = "/infer",
            body = """
                {
                  "backend": "CPU",
                  "modelId": "mansui",
                  "language": "zh",
                  "text": "hello",
                  "referenceAudioPath": "${FakeBridge.primaryReferenceJsonPath}",
                  "referenceText": "sample",
                  "maxDecoderSteps": 0
                }
            """.trimIndent(),
        )

        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("Rejected by model"))
        assertEquals(null, bridge.capturedRequest)
    }

    @Test
    fun inferRejectsAuxiliaryReferencesForLegacyModelBeforeBackendCall() {
        val bridge = FakeBridge(
            models = listOf(CharacterModel(ModelRef("gptsovits", "v2", "legacy"), "Legacy", "v2/legacy/tts_models")),
        )
        val server = LocalHttpApiServer(bridge)
        val auxiliary = File.createTempFile("aux-legacy", ".wav")

        val response = server.handleRequestForTest(
            method = "POST",
            path = "/infer",
            body = """
                {
                  "backend": "CPU",
                  "modelId": "legacy",
                  "language": "zh",
                  "text": "hello",
                  "referenceAudioPath": "${FakeBridge.primaryReferenceJsonPath}",
                  "referenceText": "sample",
                  "auxReferenceAudioPaths": ["${auxiliary.absolutePath.replace(File.separatorChar, '/')}"]
                }
            """.trimIndent(),
        )

        assertEquals(400, response.statusCode)
        assertTrue(response.body, response.body.contains("does not support"))
        assertEquals(null, bridge.capturedRequest)
        auxiliary.delete()
    }

    @Test
    fun inferMapsPromptLanguageIndependentlyFromSynthesisLanguage() {
        val bridge = FakeBridge()
        val server = LocalHttpApiServer(bridge)

        val response = server.handleRequestForTest(
            method = "POST",
            path = "/infer",
            body = """
                {
                  "backend": "CPU",
                  "modelId": "mansui",
                  "language": "en",
                  "promptLanguage": "zh",
                  "text": "hello",
                  "referenceAudioPath": "${FakeBridge.primaryReferenceJsonPath}",
                  "referenceText": "你好"
                }
            """.trimIndent(),
        )

        assertEquals(200, response.statusCode)
        assertEquals("en", bridge.capturedRequest?.language)
        assertEquals("zh", bridge.capturedRequest?.promptLanguage)
    }

    @Test
    fun inferDefaultsMissingPromptLanguageToSynthesisLanguage() {
        val bridge = FakeBridge()
        val server = LocalHttpApiServer(bridge)

        val response = server.handleRequestForTest(
            method = "POST",
            path = "/infer",
            body = """
                {
                  "backend": "CPU",
                  "modelId": "mansui",
                  "language": "ja",
                  "text": "こんにちは",
                  "referenceAudioPath": "${FakeBridge.primaryReferenceJsonPath}",
                  "referenceText": "参考テキスト"
                }
            """.trimIndent(),
        )

        assertEquals(200, response.statusCode)
        assertEquals("ja", bridge.capturedRequest?.promptLanguage)
    }

    @Test
    fun modelsEndpointReturnsCharacterList() {
        val server = LocalHttpApiServer(FakeBridge())

        val response = server.handleRequestForTest(method = "GET", path = "/models")

        assertEquals(200, response.statusCode)
        assertTrue(response.body.contains("\"models\":["))
        assertTrue(response.body.contains("\"id\":\"mansui\""))
    }

    @Test
    fun inferRejectsBackendThatIsNotEnabled() {
        val bridge = FakeBridge(
            enabledBackends = setOf(ExecutionBackend.CPU),
        )
        val server = LocalHttpApiServer(bridge)

        val response = server.handleRequestForTest(
            method = "POST",
            path = "/infer",
            body = """
                {
                  "backend": "WEBGPU",
                  "modelId": "mansui",
                  "language": "zh",
                  "text": "hello",
                  "referenceAudioPath": "${FakeBridge.primaryReferenceJsonPath}",
                  "referenceText": "sample"
                }
            """.trimIndent(),
        )

        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("Backend is not enabled"))
    }

    @Test
    fun getInferUsesActiveDefaultsForMissingParams() {
        val bridge = FakeBridge()
        val server = LocalHttpApiServer(bridge)

        val response = server.handleRequestForTest(
            method = "GET",
            path = "/infer",
            queryParams = mapOf("text" to "hello world"),
        )

        assertEquals(200, response.statusCode)
        assertEquals("audio/wav", response.contentType)
        assertTrue(response.binaryBody?.isNotEmpty() == true)
        assertEquals(ExecutionBackend.CPU, bridge.capturedBackend)
        assertEquals("hello world", bridge.capturedRequest?.synthesisText)
        assertEquals("mansui", bridge.capturedRequest?.characterModel?.id)
        assertEquals(FakeBridge.primaryReferencePath, bridge.capturedRequest?.referenceAudioPath)
        assertEquals("sample", bridge.capturedRequest?.referenceText)
        assertEquals("zh", bridge.capturedRequest?.language)
    }

    @Test
    fun getInferUsesIndependentPromptLanguageFromActiveDefaults() {
        val bridge = FakeBridge(
            defaults = ActiveDefaults(
                backend = ExecutionBackend.CPU,
                model = ModelRef("gptsovits", "v2ProPlus", "mansui"),
                language = "en",
                promptLanguage = "zh",
                referenceAudioPath = FakeBridge.primaryReferencePath,
                referenceText = "sample",
                options = mapOf("maxDecoderSteps" to "500"),
            ),
        )
        val server = LocalHttpApiServer(bridge)

        val response = server.handleRequestForTest(
            method = "GET",
            path = "/infer",
            queryParams = mapOf("text" to "hello world"),
        )

        assertEquals(200, response.statusCode)
        assertEquals("en", bridge.capturedRequest?.language)
        assertEquals("zh", bridge.capturedRequest?.promptLanguage)
    }

    @Test
    fun cancelRouteRequiresAndPassesTheExactRequestIdentity() {
        var cancelledId: String? = null
        val bridge = object : LocalHttpApiServer.Bridge by FakeBridge() {
            override fun cancel(requestId: String): Boolean {
                cancelledId = requestId
                return requestId == "active-request"
            }
        }
        val server = LocalHttpApiServer(bridge)
        assertEquals(400, server.handleRequestForTest("POST", "/cancel").statusCode)
        assertEquals(null, cancelledId)
        assertEquals(404, server.handleRequestForTest("POST", "/cancel", queryParams = mapOf("requestId" to "old-request")).statusCode)
        assertEquals(200, server.handleRequestForTest("POST", "/cancel", queryParams = mapOf("requestId" to "active-request")).statusCode)
        assertEquals("active-request", cancelledId)
    }

    private class FakeBridge(
        private val validate: (Map<String, String>) -> Map<String, String> = { it },
        private val enabledBackends: Set<ExecutionBackend> = setOf(ExecutionBackend.CPU),
        private val models: List<CharacterModel> = listOf(testModel),
        private val defaults: ActiveDefaults = ActiveDefaults(
            backend = ExecutionBackend.CPU,
            model = ModelRef("gptsovits", "v2ProPlus", "mansui"),
            language = "zh",
            referenceAudioPath = FakeBridge.primaryReferencePath,
            referenceText = "sample",
            options = mapOf("maxDecoderSteps" to "500"),
        ),
    ) : LocalHttpApiServer.Bridge {
        companion object {
            val primaryReferencePath: String = File.createTempFile("voicebox-primary", ".wav").apply {
                writeBytes(byteArrayOf(0))
                deleteOnExit()
            }.absolutePath
            val primaryReferenceJsonPath: String = primaryReferencePath.replace(File.separatorChar, '/')
        }

        var capturedBackend: ExecutionBackend? = null
        var capturedRequest: GenerationRequest? = null

        override fun currentState(): BackendServiceState = BackendServiceState(message = "Idle")

        override fun validateOptions(model: CharacterModel, options: Map<String, String>) =
            validate(options)

        override fun availableModels(): List<CharacterModel> = models

        override fun supportedBackends(model: CharacterModel): Set<ExecutionBackend> = enabledBackends

        override fun activeDefaults(): ActiveDefaults = defaults

        override fun infer(backend: ExecutionBackend, request: GenerationRequest): Boolean {
            capturedBackend = backend
            capturedRequest = request
            return true
        }

        override fun inferBlocking(backend: ExecutionBackend, request: GenerationRequest, timeoutMs: Long): InferResult {
            capturedBackend = backend
            capturedRequest = request
            val tempFile = File.createTempFile("tts_test", ".wav")
            tempFile.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00))
            return InferResult.Success(tempFile, 1000)
        }
    }
}
