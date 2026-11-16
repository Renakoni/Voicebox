package dev.rinstel.genie_tts.inference

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeviceAccelerationTest {
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
