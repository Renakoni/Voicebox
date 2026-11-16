package dev.rinstel.genie_tts.inference

data class ChineseWordEntry(
    val frequency: Int,
    val tag: String,
    val pinyinCsv: String,
)

data class ChineseG2pResources(
    val polyphonicPinyin: Map<String, List<String>>,
    val opencpopByPinyin: Map<String, String>,
    val singleCharPinyin: Map<String, String>,
    val wordEntries: Map<String, ChineseWordEntry> = emptyMap(),
    val traditionalToSimplified: Map<Char, String> = emptyMap(),
) {
    val maxWordLength: Int = wordEntries.keys.maxOfOrNull(String::length) ?: 1
    val totalWordFrequency: Double = wordEntries.values.sumOf { it.frequency.toLong() }.coerceAtLeast(1).toDouble()

    fun wordFrequency(word: String): Int = wordEntries[word]?.frequency ?: 0

    fun wordTag(word: String): String = wordEntries[word]?.tag.orEmpty()

    fun wordPinyinFor(word: String): List<String>? = wordEntries[word]?.pinyinCsv?.takeIf { it.isNotEmpty() }?.split(',')
}
