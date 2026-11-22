package dev.renakoni.voicebox.model.gptsovits

import android.content.Context

interface TextFeatureExtractor : AutoCloseable {
    fun extract(language: String, text: String): TextFeatures
    override fun close() = Unit
}

class DefaultTextFeatureExtractor(
    private val chinese: ChineseTextFeatureExtractor,
    private val japanese: TextFeatureExtractor = UnsupportedLanguageTextFeatureExtractor(),
    private val english: TextFeatureExtractor = UnsupportedLanguageTextFeatureExtractor(),
    private val korean: TextFeatureExtractor = UnsupportedLanguageTextFeatureExtractor(),
    private val fallback: TextFeatureExtractor,
) : TextFeatureExtractor {
    override fun close() { japanese.close(); english.close(); korean.close(); fallback.close() }
    override fun extract(language: String, text: String): TextFeatures {
        val input = ChineseTextNormalizer.convertFullwidthToHalfwidth(text)
        val selected = LanguageNormalizer.resolve(language, input)
        return when (selected) {
            "chinese", "hybrid-chinese-english" -> hybrid.extract(selected, input)
            "japanese" -> japanese.extract(selected, input)
            "english" -> english.extract(selected, input)
            "korean" -> korean.extract(selected, input)
            else -> fallback.extract(selected, input)
        }
    }

    private val hybrid: TextFeatureExtractor = HybridTextFeatureExtractor(chinese, english)

    companion object {
        fun fromAssets(
            context: Context,
            robertaProvider: RobertaFeatureProvider? = null,
        ): DefaultTextFeatureExtractor =
            DefaultTextFeatureExtractor(
                chinese = ChineseTextFeatureExtractor(
                    ChineseG2pAssetRepository.load(context),
                    robertaProvider,
                    allowZeroBert = false,
                ),
                japanese = JapaneseTextFeatureExtractor.fromAssets(context),
                english = EnglishTextFeatureExtractor.fromAssets(context),
                fallback = UnsupportedLanguageTextFeatureExtractor(),
            )
    }
}

/** Combines language-specific outputs in the same order as Python split_language. */
class HybridTextFeatureExtractor(
    private val chinese: ChineseTextFeatureExtractor,
    private val english: TextFeatureExtractor,
) : TextFeatureExtractor {
    override fun extract(language: String, text: String): TextFeatures {
        val chunks = mutableListOf<TextFeatures>()
        var englishStart = -1
        var englishEnd = 0
        for (word in LanguageNormalizer.englishWords(text)) {
            val start = word.range.first
            val between = text.substring(englishEnd, start)
            if (englishStart < 0 || between.any { !it.isWhitespace() }) {
                if (englishStart >= 0) chunks += english.extract("english", text.substring(englishStart, englishEnd))
                appendChineseChunk(chunks, between)
                englishStart = start
            }
            englishEnd = word.range.last + 1
        }
        if (englishStart < 0) return chinese.extract(text)
        chunks += english.extract("english", text.substring(englishStart, englishEnd))
        appendChineseChunk(chunks, text.substring(englishEnd))

        val symbols = chunks.flatMap(TextFeatures::symbols)
        val phoneValues = LongArray(symbols.size)
        var phoneOffset = 0
        val bertValues = FloatArray(symbols.size * ChineseTextFeatureExtractor.BERT_FEATURE_DIM)
        var bertOffset = 0
        for (chunk in chunks) {
            chunk.phoneIds.values.copyInto(phoneValues, phoneOffset)
            phoneOffset += chunk.phoneIds.values.size
            chunk.bert.values.copyInto(bertValues, bertOffset)
            bertOffset += chunk.bert.values.size
        }

        return TextFeatures(
            normalizedText = chunks.joinToString(separator = "") { it.normalizedText },
            symbols = symbols,
            phoneIds = LongTensorData(
                values = phoneValues,
                shape = longArrayOf(1L, phoneValues.size.toLong()),
            ),
            bert = FloatTensorData(
                values = bertValues,
                shape = longArrayOf(
                    symbols.size.toLong(),
                    ChineseTextFeatureExtractor.BERT_FEATURE_DIM.toLong(),
                ),
            ),
        )
    }

    private fun appendChineseChunk(chunks: MutableList<TextFeatures>, chunk: String) {
        if (chunk.isBlank()) return
        val features = chinese.extract(chunk)
        if (features.normalizedText.isNotEmpty()) chunks += features
    }
}

class UnsupportedLanguageTextFeatureExtractor : TextFeatureExtractor {
    override fun extract(language: String, text: String): TextFeatures {
        throw UnsupportedOperationException(
            "Language '$language' is not migrated on Android yet. " +
                "Available offline frontends: Chinese, Japanese, English and Chinese-English.",
        )
    }
}

object GenieTextConventions {
    fun prepareSynthesisText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return trimmed
        }
        return "\u3002$trimmed"
    }
}

data class TextFeatures(
    val normalizedText: String,
    val symbols: List<String>,
    val phoneIds: LongTensorData,
    val bert: FloatTensorData,
)

internal fun symbolsToTensor(symbols: List<String>): LongTensorData =
    LongTensorData(
        values = symbols
            .map { symbol -> GenieSymbols.symbolToId[symbol] ?: GenieSymbols.symbolToId.getValue("UNK") }
            .map(Int::toLong)
            .toLongArray(),
        shape = longArrayOf(1L, symbols.size.toLong()),
    )

internal fun zeroBert(phoneCount: Int): FloatTensorData =
    FloatTensorData(
        values = FloatArray(phoneCount * ChineseTextFeatureExtractor.BERT_FEATURE_DIM),
        shape = longArrayOf(phoneCount.toLong(), ChineseTextFeatureExtractor.BERT_FEATURE_DIM.toLong()),
    )
