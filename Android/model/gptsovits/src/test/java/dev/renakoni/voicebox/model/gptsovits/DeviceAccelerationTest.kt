package dev.renakoni.voicebox.model.gptsovits

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeviceAccelerationTest {
    @Test fun reusesProbeResultsUntilEnvironmentChangesOrCacheIsCleared() {
        val root = Files.createTempDirectory("npu-capability").toFile()
        try {
            val runtime = File(root, "runtime").apply { mkdir() }
            val library = File(runtime, "libQnnHtp.so").apply { writeBytes(byteArrayOf(1)) }
            val cache = File(root, "capability")
            var calls = 0
            var supported = true
            fun detect(system: String = "system-a", updatedAt: Long = 1): Boolean {
                val key = DeviceAcceleration.capabilityCacheKey(system, updatedAt, runtime)
                return DeviceAcceleration.cachedCapability(cache, key) { calls++; supported }
            }
            assertTrue(detect())
            supported = false
            assertTrue(detect())
            assertEquals(1, calls)
            assertFalse(detect("system-b"))
            assertFalse(detect("system-b"))
            assertEquals(2, calls)
            assertFalse(detect("system-b", 2))
            assertEquals(3, calls)
            library.appendBytes(byteArrayOf(2))
            assertFalse(detect("system-b", 2))
            assertEquals(4, calls)
            cache.delete()
            supported = true
            assertTrue(detect("system-b", 2))
            assertEquals(5, calls)
        } finally { root.deleteRecursively() }
    }

    @Test fun separatesPreparedContextsByModelPlatformShapeAndFileContents() {
        val dir = Files.createTempDirectory("gpt-contexts").toFile()
        try {
            val runtime = File(dir, "runtime").apply { mkdir() }
            val library = File(runtime, "libQnnHtp.so").apply { writeText("qnn") }
            val graph = File(dir, "body.onnx").apply { writeText("graph") }
            val weights = File(dir, "weights.bin").apply { writeText("weights") }
            fun cache(family: String = "v5", platform: String = "device/arm64", frames: Int = 64) =
                QnnSession.Model(runtime, graph, weights, family, platform).cache(frames)
            val first = cache().apply { writeText("compiled") }
            assertEquals(first, cache())
            assertNotEquals(first, cache(frames = 128))
            assertNotEquals(first, cache(family = "v2"))
            assertTrue(first.isFile)
            for (file in listOf(weights, graph, library)) {
                val before = cache().apply { writeText("compiled") }
                val mtime = file.lastModified()
                file.writeText(file.readText().uppercase())
                file.setLastModified(mtime)
                assertNotEquals(before, cache())
                assertFalse(before.exists())
            }
            assertNotEquals(cache(), cache(platform = "device/x86_64"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun ignoresPhoneBrandAndRequiresMatchingRuntimePair() {
        assertTrue(DeviceAcceleration.isQualcomm("QUALCOMM", "unknown"))
        assertTrue(DeviceAcceleration.isQualcomm("QTI", "unknown"))
        assertTrue(DeviceAcceleration.isQualcomm("unknown", "qcom"))
        assertFalse(DeviceAcceleration.isQualcomm("MediaTek", "mt6989"))
        assertFalse(DeviceAcceleration.isQualcomm("Xiaomi", "mt6989"))
        val root = Files.createTempDirectory("npu-components").toFile()
        try {
            assertFalse(DeviceAcceleration.hasRuntimeFiles(root))
            listOf("libonnxruntime-qnn.so", "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so",
                "libQnnHtpV75Stub.so", "libQnnHtpV73Skel.so").forEach { File(root, it).writeBytes(byteArrayOf(1)) }
            assertFalse(DeviceAcceleration.hasRuntimeFiles(root))
            File(root, "libQnnHtpV75Skel.so").writeBytes(byteArrayOf(1))
            assertTrue(DeviceAcceleration.hasRuntimeFiles(root))
            File(root, "libonnxruntime-qnn.so").delete()
            assertFalse(DeviceAcceleration.hasRuntimeFiles(root))
        } finally { root.deleteRecursively() }
    }
}
