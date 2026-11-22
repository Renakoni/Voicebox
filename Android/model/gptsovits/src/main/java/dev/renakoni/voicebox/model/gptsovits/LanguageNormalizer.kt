package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.core.LanguageTags
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.requireUi
import java.lang.Character.UnicodeScript

object LanguageNormalizer {
    private const val LatinWord = """\p{IsLatin}[\p{IsLatin}\p{M}]*"""
    private val englishWord = Regex("""$LatinWord(?:['’-]$LatinWord)*""")

    internal fun resolve(language: String, text: String): String {
        val selected = LanguageTags.normalize(language)
        if (selected != "auto") {
            explicitScriptError(selected, text)?.let { requireUi(false) { UiText(it) } }
            return selected
        }

        var hasHan = false
        val codePoints = text.codePoints().iterator()
        while (codePoints.hasNext()) {
            when (UnicodeScript.of(codePoints.nextInt())) {
                UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> return "japanese"
                UnicodeScript.HAN -> hasHan = true
                else -> Unit
            }
        }
        return if (!hasHan && englishWords(text).any()) "english" else "chinese"
    }

    fun scriptError(language: String, text: String): String? =
        explicitScriptError(LanguageTags.normalize(language), text)

    private fun explicitScriptError(language: String, text: String): String? = when (language) {
        "chinese", "hybrid-chinese-english" ->
            "language_contains_kana".takeIf { text.codePoints().anyMatch { isKana(UnicodeScript.of(it)) } }
        "english" -> "language_not_english".takeIf {
            text.codePoints().anyMatch {
                val script = UnicodeScript.of(it)
                script == UnicodeScript.HAN || isKana(script)
            }
        }
        else -> null
    }

    internal fun englishWords(text: String): Sequence<MatchResult> = englishWord.findAll(text).filterNot { word ->
        var before = word.range.first - 1
        while (before >= 0 && text[before].isWhitespace()) before--
        val previous = text.getOrNull(before)
        (previous?.isDigit() == true && ChineseTextNormalizer.isMeasure(word.value)) ||
            (previous == '°' && word.value == "C")
    }

    private fun isKana(script: UnicodeScript) = script == UnicodeScript.HIRAGANA || script == UnicodeScript.KATAKANA
}
