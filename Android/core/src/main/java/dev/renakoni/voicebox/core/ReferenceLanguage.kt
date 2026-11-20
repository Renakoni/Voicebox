package dev.renakoni.voicebox.core


/** Resolve automatic reference language from the supplied transcript. */
object ReferenceLanguage {
    fun resolve(language: String, text: String): String = when {
        language.isNotBlank() && language != "auto" -> LanguageTags.normalize(language)
        text.any { it in '\u3040'..'\u30ff' || it in '\uff66'..'\uff9d' } -> "japanese"
        text.any { it in '\u3400'..'\u9fff' } -> "chinese"
        text.any { it in 'a'..'z' || it in 'A'..'Z' } -> "english"
        else -> "chinese"
    }
}
