package dev.rinstel.genie_tts.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReferenceConditioningCacheTest {
    @Test
    fun defaultCachesReuseTwoVoicesAndEvictTheLeastRecentlyUsedContext() {
        val referenceCache = ReferenceConditioningCache()
        val firstReference = key().copy(characterModelId = "feibi")
        val secondReference = key().copy(characterModelId = "mansui")
        val thirdReference = key().copy(characterModelId = "third")

        val first = referenceCache.getOrPut(firstReference) { conditioning(1) }
        referenceCache.getOrPut(secondReference) { conditioning(2) }

        assertSame(first, referenceCache.getOrPut(firstReference) { error("Must reuse the original voice") })
        referenceCache.getOrPut(thirdReference) { conditioning(3) }
        assertSame(first, referenceCache.get(firstReference))
        assertNull(referenceCache.get(secondReference))
        assertNotNull(referenceCache.get(thirdReference))

        val promptCache = PromptConditioningCache()
        val firstPrompt = promptKey(
            primary = ReferenceAudioFingerprint("/ref/one.wav", 1L, 1L),
            auxiliary = emptyList(),
        ).copy(characterModelId = "feibi")
        val secondPrompt = firstPrompt.copy(characterModelId = "mansui")
        val thirdPrompt = firstPrompt.copy(characterModelId = "third")
        val firstEmbeddings = promptCache.getOrPut(firstPrompt) { promptEmbeddings(1f) }
        promptCache.getOrPut(secondPrompt) { promptEmbeddings(2f) }

        assertSame(firstEmbeddings, promptCache.getOrPut(firstPrompt) { error("Must reuse the original voice") })
        promptCache.getOrPut(thirdPrompt) { promptEmbeddings(3f) }
        assertSame(firstEmbeddings, promptCache.get(firstPrompt))
        assertNull(promptCache.get(secondPrompt))
        assertNotNull(promptCache.get(thirdPrompt))

        referenceCache.clear()
        promptCache.clear()
        assertNull(referenceCache.get(firstReference))
        assertNull(referenceCache.get(thirdReference))
        assertNull(promptCache.get(firstPrompt))
        assertNull(promptCache.get(thirdPrompt))
    }

    @Test
    fun promptCacheKeepsIndependentAuxiliaryBundles() {
        val cache = PromptConditioningCache(maxEntries = 2)
        val primary = ReferenceAudioFingerprint("/ref/primary.wav", 1L, 1L)
        val firstAuxiliary = ReferenceAudioFingerprint("/ref/first.wav", 2L, 2L)
        val secondAuxiliary = ReferenceAudioFingerprint("/ref/second.wav", 3L, 3L)
        val firstKey = promptKey(primary, listOf(firstAuxiliary))
        val secondKey = promptKey(primary, listOf(secondAuxiliary))
        var factoryCalls = 0

        val first = cache.getOrPut(firstKey) {
            factoryCalls += 1
            PromptEmbeddings(FloatTensorData(floatArrayOf(1f), longArrayOf(1L)), FloatTensorData(floatArrayOf(1f), longArrayOf(1L)))
        }
        val second = cache.getOrPut(secondKey) {
            factoryCalls += 1
            PromptEmbeddings(FloatTensorData(floatArrayOf(2f), longArrayOf(1L)), FloatTensorData(floatArrayOf(2f), longArrayOf(1L)))
        }

        assertEquals(2, factoryCalls)
        assertEquals(1f, first.globalEmbedding.values[0], 0f)
        assertEquals(2f, second.globalEmbedding.values[0], 0f)
        assertEquals(first, cache.get(firstKey))
    }

    @Test
    fun auxiliaryPromptKeyIncludesPrimaryReferenceFingerprint() {
        val auxiliary = listOf(ReferenceAudioFingerprint("/ref/aux.wav", 2L, 2L))

        val first = promptKey(
            primary = ReferenceAudioFingerprint("/ref/primary-one.wav", 1L, 1L),
            auxiliary = auxiliary,
        )
        val replacementPrimary = promptKey(
            primary = ReferenceAudioFingerprint("/ref/primary-two.wav", 3L, 3L),
            auxiliary = auxiliary,
        )

        assertNotEquals(first, replacementPrimary)
    }

    @Test
    fun missesWhenReferenceTextChanges() {
        val cache = ReferenceConditioningCache(maxEntries = 2)
        var factoryCalls = 0

        cache.getOrPut(key(referenceText = "reference one")) {
            factoryCalls += 1
            conditioning(1)
        }
        val second = cache.getOrPut(key(referenceText = "reference two")) {
            factoryCalls += 1
            conditioning(2)
        }

        assertEquals(2, factoryCalls)
        assertEquals(longArrayOf(2L).toList(), second.refSeq.values.toList())
    }

    @Test
    fun missesWhenReferenceFileFingerprintChanges() {
        val cache = ReferenceConditioningCache(maxEntries = 2)
        val base = key()
        var factoryCalls = 0

        cache.getOrPut(base) {
            factoryCalls += 1
            conditioning(1)
        }
        val replacement = base.copy(referenceAudioSize = 42L, referenceAudioLastModified = 7L)
        cache.getOrPut(replacement) {
            factoryCalls += 1
            conditioning(2)
        }

        assertEquals(2, factoryCalls)
    }

    @Test
    fun missesWhenPreprocessingOrModelInterfaceChanges() {
        val cache = ReferenceConditioningCache(maxEntries = 4)
        val base = key()
        var factoryCalls = 0

        cache.getOrPut(base) {
            factoryCalls += 1
            conditioning(1)
        }
        cache.getOrPut(base.copy(preprocessingVersion = "reference-conditioning-v3")) {
            factoryCalls += 1
            conditioning(2)
        }
        cache.getOrPut(base.copy(modelInterfaceVersion = "new-interface")) {
            factoryCalls += 1
            conditioning(3)
        }

        assertEquals(3, factoryCalls)
    }

    private fun key(
        referenceAudioPath: String = "/ref/sample.wav",
        referenceText: String = "reference",
    ): ReferenceConditioningCacheKey =
        ReferenceConditioningCacheKey(
            characterModelId = "mansui",
            backend = ExecutionBackend.CPU,
            language = "zh",
            referenceAudioPath = referenceAudioPath,
            referenceText = referenceText,
            usesPromptEncoder = true,
        )

    private fun conditioning(value: Long): ReferenceConditioning =
        ReferenceConditioning(
            refSeq = LongTensorData(longArrayOf(value), longArrayOf(1L, 1L)),
            refBert = FloatTensorData(floatArrayOf(value.toFloat()), longArrayOf(1L, 1L)),
            sslContent = FloatTensorData(floatArrayOf(value.toFloat()), longArrayOf(1L, 1L, 1L)),
            globalEmbedding = FloatTensorData(floatArrayOf(value.toFloat()), longArrayOf(1L, 1L)),
            advancedGlobalEmbedding = FloatTensorData(floatArrayOf(value.toFloat()), longArrayOf(1L, 1L)),
            refAudio32k = null,
        )

    private fun promptEmbeddings(value: Float): PromptEmbeddings =
        PromptEmbeddings(
            globalEmbedding = FloatTensorData(floatArrayOf(value), longArrayOf(1L)),
            advancedGlobalEmbedding = FloatTensorData(floatArrayOf(value), longArrayOf(1L)),
        )

    private fun promptKey(
        primary: ReferenceAudioFingerprint,
        auxiliary: List<ReferenceAudioFingerprint>,
    ): PromptConditioningCacheKey =
        PromptConditioningCacheKey(
            characterModelId = "mansui",
            backend = ExecutionBackend.CPU,
            modelInterfaceVersion = "prompt-v2",
            preprocessingVersion = "reference-conditioning-v2",
            conditioningRole = InferenceModelRole.PROMPT_ENCODER,
            primaryReferenceAudio = primary,
            auxiliaryReferenceAudioFingerprints = auxiliary,
        )
}
