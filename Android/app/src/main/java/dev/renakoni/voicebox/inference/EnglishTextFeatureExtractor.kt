package dev.renakoni.voicebox.inference

import android.content.Context

class EnglishTextFeatureExtractor(private val closeAction: () -> Unit = {}, private val g2p: (String) -> List<String>) : TextFeatureExtractor {
    override fun close() = closeAction()
    override fun extract(language: String, text: String): TextFeatures {
        val symbols = g2p(text).filter { it in GenieSymbols.symbolToId }
        require(symbols.isNotEmpty()) { "请输入英语文字。" }
        return TextFeatures(text.trim(), symbols, symbolsToTensor(symbols), zeroBert(symbols.size))
    }

    companion object {
        fun fromAssets(context: Context): EnglishTextFeatureExtractor {
            val frontend = lazy { EnglishG2p(LanguageAssets.directory(context, "english")) }
            return EnglishTextFeatureExtractor({ if (frontend.isInitialized()) frontend.value.close() }) { frontend.value.phonemes(it) }
        }
    }
}
