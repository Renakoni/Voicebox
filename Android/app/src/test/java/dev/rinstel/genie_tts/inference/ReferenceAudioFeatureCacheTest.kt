package dev.rinstel.genie_tts.inference

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class ReferenceAudioFeatureCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun features(speaker: Boolean = true) = ReferenceAudioFeatures(
        FloatTensorData(floatArrayOf(0.0f, -0.0f, Float.MIN_VALUE, -1.75f), longArrayOf(1, 2, 2)),
        if (speaker) FloatTensorData(floatArrayOf(0.25f, -0.5f), longArrayOf(1, 2)) else null,
    )

    private fun key(name: String) = ReferenceAudioFeatureCache.key(name, "app1-cpu-v1", emptyList())

    @Test fun survivesNewInstanceWithExactTensorBits() {
        val directory = temporary.newFolder()
        val input = features()
        assertTrue(ReferenceAudioFeatureCache(directory).store(key("a"), input))
        val output = requireNotNull(ReferenceAudioFeatureCache(directory).load(key("a")))
        assertArrayEquals(input.sslContent.shape, output.sslContent.shape)
        assertArrayEquals(input.sslContent.values.map(Float::toRawBits).toIntArray(),
            output.sslContent.values.map(Float::toRawBits).toIntArray())
        assertEquals(input.speakerEmbedding, output.speakerEmbedding)
    }

    @Test fun supportsV2WithoutSpeakerFeatures() {
        val cache = ReferenceAudioFeatureCache(temporary.newFolder())
        assertTrue(cache.store(key("a"), features(false)))
        assertEquals(features(false), cache.load(key("a")))
    }

    @Test fun invalidatesForAudioRuntimeModelAndInstallChanges() {
        val model = temporary.newFile().apply { writeText("weights") }
        fun cacheKey(audio: String = "pcm-a", namespace: String = "install1-cpu-v1") =
            ReferenceAudioFeatureCache.key(audio, namespace, listOf(model))
        val first = cacheKey()
        assertNotEquals(first, cacheKey(audio = "pcm-b"))
        assertNotEquals(first, cacheKey(namespace = "install2-cpu-v1"))
        assertNotEquals(first, cacheKey(namespace = "install1-webgpu-v1"))
        assertNotEquals(first, cacheKey(namespace = "install1-cpu-v2"))
        assertTrue(model.setLastModified(model.lastModified() + 5000))
        assertNotEquals(first, cacheKey())
        val touched = cacheKey()
        model.appendText("new")
        assertNotEquals(touched, cacheKey())
    }

    @Test fun rejectsTruncationAndChangedPayload() {
        val directory = temporary.newFolder()
        val cache = ReferenceAudioFeatureCache(directory)
        assertTrue(cache.store(key("a"), features()))
        val file = File(directory, "${key("a")}.bin")
        val valid = file.readBytes()
        file.writeBytes(valid.copyOf(20))
        assertNull(cache.load(key("a")))
        valid[18] = (valid[18].toInt() xor 1).toByte()
        file.writeBytes(valid)
        assertNull(cache.load(key("a")))
        assertTrue(cache.store(key("a"), features()))
        assertNotNull(cache.load(key("a")))
    }

    @Test fun rejectsInvalidShapesEvenWithValidChecksum() {
        val directory = temporary.newFolder()
        val cache = ReferenceAudioFeatureCache(directory)
        cache.store(key("a"), features())
        val file = File(directory, "${key("a")}.bin")
        val bytes = file.readBytes()
        ByteBuffer.wrap(bytes).putLong(8, Long.MAX_VALUE)
        val payload = bytes.copyOf(bytes.size - 32)
        file.writeBytes(payload + MessageDigest.getInstance("SHA-256").digest(payload))
        assertNull(cache.load(key("a")))
    }

    @Test fun retainsOnlyTwoRecentlyUsedContexts() {
        val directory = temporary.newFolder()
        val cache = ReferenceAudioFeatureCache(directory)
        cache.store(key("a"), features())
        cache.store(key("b"), features())
        assertTrue(File(directory, "${key("a")}.bin").setLastModified(1000))
        assertTrue(File(directory, "${key("b")}.bin").setLastModified(2000))
        assertNotNull(cache.load(key("a")))
        cache.store(key("c"), features())
        assertNotNull(cache.load(key("a")))
        assertNull(cache.load(key("b")))
        assertNotNull(cache.load(key("c")))
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test fun unavailableStorageDoesNotPreventComputation() {
        val cache = ReferenceAudioFeatureCache(temporary.newFile())
        assertFalse(cache.store(key("a"), features()))
        assertNull(cache.load(key("a")))
    }

    @Test fun interruptedWritePreservesLastGoodEntryAndIsReplaced() {
        val directory = temporary.newFolder()
        val cache = ReferenceAudioFeatureCache(directory)
        cache.store(key("a"), features())
        val staging = File(directory, ".reference-audio.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(features(), cache.load(key("a")))
        assertTrue(cache.store(key("b"), features(false)))
        assertFalse(staging.exists())
        assertEquals(features(false), cache.load(key("b")))
    }
}
