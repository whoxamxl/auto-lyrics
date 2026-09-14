package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationLanguagesTest {

    @Test
    fun japaneseToEnglishNeedsJapaneseModelOnly() {
        assertEquals(
            listOf("ja"),
            TranslationLanguages.requiredModelLanguages("ja", "en")
        )
    }

    @Test
    fun englishToJapaneseNeedsJapaneseModelOnly() {
        assertEquals(
            listOf("ja"),
            TranslationLanguages.requiredModelLanguages("en", "ja")
        )
    }

    @Test
    fun japaneseToFrenchNeedsBothNonEnglishModels() {
        assertEquals(
            listOf("ja", "fr"),
            TranslationLanguages.requiredModelLanguages("ja", "fr")
        )
    }

    @Test
    fun regionalTagsNormalizeToModelLanguage() {
        assertEquals(
            listOf("ja", "pt"),
            TranslationLanguages.requiredModelLanguages("ja-JP", "pt-BR")
        )
    }

    @Test
    fun unsupportedTargetFallsBackToEnglish() {
        assertEquals(
            "en",
            TranslationLanguages.normalizeTargetLanguage("xx")
        )
    }
}
