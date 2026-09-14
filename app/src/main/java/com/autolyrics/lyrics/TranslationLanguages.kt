package com.autolyrics.lyrics

import java.util.Locale

/**
 * Central translation-language configuration.
 *
 * English remains the active target until the settings UI is wired to
 * [TARGET_LANGUAGE_PREF_KEY]. Keeping the supported target list here gives the
 * translator and the UI one source of truth as multi-language output is added.
 */
object TranslationLanguages {
    const val DEFAULT_TARGET_LANGUAGE = "en"
    const val TARGET_LANGUAGE_PREF_KEY = "translation_target_language"

    /**
     * Intentionally limited to the main languages we want to expose in Settings.
     * ML Kit supports more languages, but a smaller list keeps the UI predictable.
     */
    val primaryTargets: List<String> = listOf(
        "en", // English
        "ja", // Japanese
        "fr", // French
        "de", // German
        "es", // Spanish
        "ko", // Korean
        "zh", // Chinese
        "it", // Italian
        "pt"  // Portuguese
    )

    fun normalizeLanguageTag(languageTag: String?): String? {
        if (languageTag.isNullOrBlank()) return null
        val normalized = Locale.forLanguageTag(languageTag).language
            .ifBlank { languageTag.substringBefore('-') }
            .lowercase(Locale.US)
        return normalized.takeIf { it.isNotBlank() }
    }

    fun normalizeTargetLanguage(languageTag: String?): String {
        val normalized = normalizeLanguageTag(languageTag)
        return normalized?.takeIf { it in primaryTargets } ?: DEFAULT_TARGET_LANGUAGE
    }

    /**
     * ML Kit translation models are language-specific, not source/target-pair files.
     * English is built in; translating between two non-English languages therefore
     * requires both language packs, which ML Kit uses through English internally.
     */
    fun requiredModelLanguages(sourceLanguage: String, targetLanguage: String): List<String> {
        val source = normalizeLanguageTag(sourceLanguage)
        val target = normalizeLanguageTag(targetLanguage)

        return listOfNotNull(source, target)
            .filter { it != DEFAULT_TARGET_LANGUAGE }
            .distinct()
    }
}
