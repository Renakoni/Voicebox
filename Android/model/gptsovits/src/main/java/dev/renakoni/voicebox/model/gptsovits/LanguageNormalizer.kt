package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.core.LanguageTags
import dev.renakoni.voicebox.core.i18n.UiText

object LanguageNormalizer {
    fun isChinese(language: String): Boolean =
        LanguageTags.normalize(language) == "chinese"

    fun isEnglish(language: String): Boolean =
        LanguageTags.normalize(language) == "english"

    fun isJapanese(language: String): Boolean =
        LanguageTags.normalize(language) == "japanese"

    fun isKorean(language: String): Boolean =
        LanguageTags.normalize(language) == "korean"

    fun isHybridChineseEnglish(language: String): Boolean =
        LanguageTags.normalize(language) == "hybrid-chinese-english"

    fun scriptError(language: String, text: String): UiText? = when {
        (isChinese(language) || isHybridChineseEnglish(language)) &&
            text.any { it in '\u3040'..'\u30ff' || it in '\uff66'..'\uff9d' } ->
            UiText("language_contains_kana")
        isEnglish(language) && text.any { it in '\u3000'..'\u9fff' && it.isLetter() } ->
            UiText("language_not_english")
        else -> null
    }

    /** Mirrors the Python frontend guard against Chinese G2P dropping ASCII letters. */
    fun shouldAutoDetectHybrid(language: String, text: String): Boolean =
        isChinese(language) && text.any { it in 'a'..'z' || it in 'A'..'Z' }
}
