package dev.rinstel.genie_tts

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.rinstel.genie_tts.api.ActiveDefaults
import dev.rinstel.genie_tts.api.InferResult
import dev.rinstel.genie_tts.inference.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Requires the locked public runtime, feibi and mansui packages on the target device. */
@RunWith(AndroidJUnit4::class)
class GenerationLifecycleInstrumentedTest {
    @get:Rule
    val foreground = ActivityScenarioRule(BenchmarkHostActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backend = ExecutionBackend.valueOf(InstrumentationRegistry.getArguments().getString("backend", "CPU"))

    private fun bind(): Pair<GenieBackendService, ServiceConnection> {
        val ready = CountDownLatch(1)
        var service: GenieBackendService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as GenieBackendService.LocalBinder).getService()
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        context.startForegroundService(GenieBackendService.serviceIntent(context))
        assertTrue(context.bindService(GenieBackendService.serviceIntent(context), connection, Context.BIND_AUTO_CREATE))
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        return requireNotNull(service) to connection
    }

    private fun request(long: Boolean = false, voiceId: String = "feibi"): GenerationRequest {
        val repository = ModelAssetRepository(context)
        val model = repository.discoverCharacterModels().first { it.id == voiceId }
        val reference = requireNotNull(repository.findReferenceExample(model))
        return GenerationRequest(
            characterModel = model, language = "zh",
            synthesisText = if (long) "陈长安走到桥边，望着远处渐渐亮起的灯火。他从口袋里取出那封信，仔细读了一遍，才轻声说道：明天上午九点，我们在银行门口见面。" else "清晨的阳光穿过窗帘，轻轻落在书页上。",
            referenceAudioPath = reference.audioFile.absolutePath,
            referenceText = reference.referenceText,
            maxDecoderSteps = 1500,
        )
    }

    private fun await(timeout: Long = 90_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var reached = condition()
        while (!reached && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20)
            reached = condition()
        }
        assertTrue("Condition was not reached in $timeout ms", reached)
    }

    private fun assertWakeLocks(generating: Boolean) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys power")
        val active = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            reader.readText().lineSequence().filter { it.contains("PARTIAL_WAKE_LOCK") }.toList()
        }
        assertFalse("Idle API must not prevent sleep: $active", active.any { it.contains("${context.packageName}:HttpServer") })
        assertEquals("Generation wake lock: $active", generating, active.any { it.contains("${context.packageName}:Generation") })
    }

    @Test
    fun timeoutNativeCancelVoiceChangeAndDestroyLeaveNextRequestUsable() {
        var (service, connection) = bind()
        try {
            assertWakeLocks(generating = false)
            val timed = request()
            assertEquals(InferResult.Timeout, service.inferBlocking(backend, timed, 1))
            await { service.currentState().requestId == timed.context.requestId && !service.currentState().busy }
            assertNull(service.currentState().latestOutputFilePath)
            assertWakeLocks(generating = false)

            val native = request(long = true)
            await { service.synthesize(backend, native) }
            await { service.currentState().requestId == native.context.requestId &&
                service.currentState().progressLabel?.let { it.startsWith("Decoder ") && !it.startsWith("Decoder 0/") } == true }
            assertWakeLocks(generating = true)
            val cancelledAt = SystemClock.elapsedRealtime()
            assertTrue(service.cancelGeneration(native.context.requestId))
            await(10_000) { service.currentState().stage == GenerationStage.CANCELLED }
            assertTrue(SystemClock.elapsedRealtime() - cancelledAt < 10_000)
            assertNull(service.currentState().latestOutputFilePath)
            assertWakeLocks(generating = false)

            // A real next request must reach audio delivery after native termination.
            SystemClock.sleep(100)
            val next = request()
            assertTrue(service.inferBlocking(backend, next, 90_000) is InferResult.Success)
            assertWakeLocks(generating = false)

            if (backend == ExecutionBackend.WEBGPU) {
                val gpu = request(long = true)
                await { service.synthesize(backend, gpu) }
                await { service.currentState().requestId == gpu.context.requestId && service.currentState().progressLabel == "Vocoder" }
                SystemClock.sleep(700)
                assertWakeLocks(generating = true)
                assertTrue(service.cancelGeneration(gpu.context.requestId))
                await(15_000) { service.currentState().stage == GenerationStage.CANCELLED }
                assertNull(service.currentState().latestOutputFilePath)
                assertWakeLocks(generating = false)
                assertTrue(service.inferBlocking(backend, request(), 90_000) is InferResult.Success)
            }

            val defaults = ActiveDefaults(backend, "feibi", "zh", next.referenceAudioPath, next.referenceText, 1500)
            service.setActiveDefaults(defaults)
            val switching = request(long = true)
            await { service.synthesize(backend, switching) }
            await { service.currentState().requestId == switching.context.requestId && service.currentState().stage == GenerationStage.RUNNING_INFERENCE }
            val mansui = request(voiceId = "mansui")
            service.setActiveDefaults(ActiveDefaults(
                backend, "mansui", "zh", mansui.referenceAudioPath, mansui.referenceText, 1500,
            ))
            await(10_000) { service.currentState().errorCode == "VOICE_CHANGED" }
            assertNull(service.currentState().latestOutputFilePath)
            assertEquals("VOICE_CHANGED", switching.context.cancellationReason)
            val switchedResult = service.inferBlocking(backend, mansui, 120_000)
            assertTrue("New voice failed: $switchedResult", switchedResult is InferResult.Success)
            assertEquals("mansui", service.currentState().initializedModelId)

            val exiting = request(long = true, voiceId = "mansui")
            await { service.synthesize(backend, exiting) }
            await { service.currentState().requestId == exiting.context.requestId && service.currentState().stage == GenerationStage.RUNNING_INFERENCE }
            context.unbindService(connection)
            context.stopService(GenieBackendService.serviceIntent(context))
            await(10_000) { exiting.context.cancellationReason == "SERVICE_DESTROYED" }
            assertFalse(service.synthesize(backend, request()))
            SystemClock.sleep(1000)
            assertWakeLocks(generating = false)
            val rebound = bind()
            service = rebound.first
            connection = rebound.second
            assertTrue(service.inferBlocking(backend, request(), 90_000) is InferResult.Success)
            if (backend == ExecutionBackend.WEBGPU) {
                assertTrue(service.inferBlocking(ExecutionBackend.CPU, request(), 90_000) is InferResult.Success)
                assertTrue(service.inferBlocking(backend, request(), 90_000) is InferResult.Success)
            }
        } finally {
            runCatching { context.unbindService(connection) }
            context.stopService(GenieBackendService.serviceIntent(context))
        }
    }
}
