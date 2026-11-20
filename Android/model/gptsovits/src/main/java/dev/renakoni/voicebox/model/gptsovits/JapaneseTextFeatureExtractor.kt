package dev.renakoni.voicebox.model.gptsovits

import android.content.Context
import java.io.File
import java.util.Locale

/** Genie JapaneseG2P.py: OpenJTalk full-context labels, including accent markers. */
class JapaneseTextFeatureExtractor(private val labels: (String) -> List<String>) : TextFeatureExtractor {
    override fun extract(language: String, text: String): TextFeatures {
        val normalized = Regex("([,./?!~…・])\\1+").replace(
            text.replace("%", "パーセント").replace("％", "パーセント"), "$1").lowercase(Locale.ROOT)
        val phones = mutableListOf<String>()
        var start = 0
        marks.findAll(normalized).forEach { match ->
            if (match.range.first > start) phones += prosody(labels(normalized.substring(start, match.range.first)))
            val mark = match.value.trim()
            if (mark.isNotEmpty()) phones += replacements[mark] ?: mark
            start = match.range.last + 1
        }
        if (start < normalized.length) phones += prosody(labels(normalized.substring(start)))
        val symbols = phones.filter { it in GenieSymbols.symbolToId }
        require(symbols.isNotEmpty()) { "请输入日语文字。" }
        return TextFeatures(normalized, symbols, symbolsToTensor(symbols), zeroBert(symbols.size))
    }

    private fun prosody(labels: List<String>): List<String> = buildList {
        labels.forEachIndexed { i, label ->
            var phone = phoneme.find(label)?.groupValues?.get(1) ?: return@forEachIndexed
            if (phone in listOf("A", "E", "I", "O", "U")) phone = phone.lowercase(Locale.ROOT)
            // Genie removes the outer ^ and $/? symbols for each punctuation-delimited segment.
            if (phone == "sil") return@forEachIndexed
            if (phone == "pau") { add("_"); return@forEachIndexed }
            add(phone)
            val a1 = feature(accent, label)
            val a2 = feature(position, label)
            val a3 = feature(remaining, label)
            val f1 = feature(moras, label)
            val next = feature(position, labels.getOrElse(i + 1) { "" })
            if (a3 == 1 && next == 1 && phone in "aeiouAEIOUNcl") add("#")
            else if (a1 == 0 && next == a2 + 1 && a2 != f1) add("]")
            else if (a2 == 1 && next == 2) add("[")
        }
    }

    companion object {
        private val marks = Regex("[^A-Za-z\\p{Nd}\\u3005\\u3040-\\u30ff\\u4e00-\\u9fff\\uff11-\\uff19\\uff21-\\uff3a\\uff41-\\uff5a\\uff66-\\uff9d]")
        private val replacements = mapOf("：" to ",", "；" to ",", "，" to ",", "。" to ".",
            "！" to "!", "？" to "?", "\n" to ".", "·" to ",", "、" to ",")
        private val phoneme = Regex("-(.*?)\\+")
        private val accent = Regex("/A:([0-9-]+)\\+")
        private val position = Regex("\\+(\\d+)\\+")
        private val remaining = Regex("\\+(\\d+)/")
        private val moras = Regex("/F:(\\d+)_")
        private fun feature(regex: Regex, label: String) = regex.find(label)?.groupValues?.get(1)?.toInt() ?: -50
        fun fromAssets(context: Context) = JapaneseTextFeatureExtractor { OpenJtalk.labels(context, it) }
    }
}

internal object OpenJtalk {
    private var dictionary: File? = null
    private external fun labelsNative(dictionary: String, text: ByteArray): Array<String>

    @Synchronized
    fun labels(context: Context, text: String): List<String> {
        if (dictionary == null) {
            System.loadLibrary("voicebox_japanese")
            dictionary = LanguageAssets.directory(context, "japanese")
        }
        return labelsNative(dictionary!!.absolutePath, text.toByteArray(Charsets.UTF_8)).toList()
    }
}
