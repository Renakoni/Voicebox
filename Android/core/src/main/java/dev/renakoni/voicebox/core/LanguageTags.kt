package dev.renakoni.voicebox.core

object LanguageTags {
    private val languageMap = mapOf(
        "chinese" to "chinese",
        "zh" to "chinese",
        "zh-cn" to "chinese",
        "zh-tw" to "chinese",
        "zh-hans" to "chinese",
        "zh-hant" to "chinese",

        "english" to "english",
        "en" to "english",
        "en-us" to "english",
        "en-gb" to "english",
        "eng" to "english",

        "japanese" to "japanese",
        "jp" to "japanese",
        "ja" to "japanese",
        "nihongo" to "japanese",

        "hybrid" to "hybrid-chinese-english",
        "hybrid-zh-en" to "hybrid-chinese-english",
        "hybrid-en-zh" to "hybrid-chinese-english",

        "korean" to "korean",
        "ko" to "korean",
        "kr" to "korean",
        "hangul" to "korean",
    )

    fun normalize(language: String): String =
        languageMap[language.lowercase().trim()] ?: language.lowercase().trim()
}
