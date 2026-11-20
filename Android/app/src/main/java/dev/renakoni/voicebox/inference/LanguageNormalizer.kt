package dev.renakoni.voicebox.inference

import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.i18n.UiText

object LanguageNormalizer {
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

    fun isChinese(language: String): Boolean =
        normalize(language) == "chinese"

    fun isEnglish(language: String): Boolean =
        normalize(language) == "english"

    fun isJapanese(language: String): Boolean =
        normalize(language) == "japanese"

    fun isKorean(language: String): Boolean =
        normalize(language) == "korean"

    fun isHybridChineseEnglish(language: String): Boolean =
        normalize(language) == "hybrid-chinese-english"

    fun scriptError(language: String, text: String): UiText? = when {
        (isChinese(language) || isHybridChineseEnglish(language)) &&
            text.any { it in '\u3040'..'\u30ff' || it in '\uff66'..'\uff9d' } ->
            UiText(R.string.language_contains_kana)
        isEnglish(language) && text.any { it in '\u3000'..'\u9fff' && it.isLetter() } ->
            UiText(R.string.language_not_english)
        else -> null
    }

    /** Mirrors the Python frontend guard against Chinese G2P dropping ASCII letters. */
    fun shouldAutoDetectHybrid(language: String, text: String): Boolean =
        isChinese(language) && text.any { it in 'a'..'z' || it in 'A'..'Z' }
}
