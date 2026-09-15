package com.autolyrics.media

import com.autolyrics.model.LyricsState

internal fun clearTranslationForLyricsVariantSwitch(state: LyricsState): LyricsState {
    return state.copy(
        translatedLines = null,
        detectedLanguage = null
    )
}
