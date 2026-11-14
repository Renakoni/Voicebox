package dev.rinstel.genie_tts.inference

/** Text-based default for old packages without language metadata, never audio recognition. */
object ReferenceLanguage {
    fun resolve(language: String, text: String): String = when {
        language.isNotBlank() && language != "auto" -> LanguageNormalizer.normalize(language)
        text.any { it in '\u3040'..'\u30ff' || it in '\uff66'..'\uff9d' } -> "japanese"
        text.any { it in '\u3400'..'\u9fff' } -> "chinese"
        text.any { it in 'a'..'z' || it in 'A'..'Z' } -> "english"
        else -> "chinese"
    }
}
