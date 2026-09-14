package com.autolyrics.util

import com.autolyrics.model.LyricLine

/**
 * Reconstructs separators around timed lyric words from the provider's original
 * line text. This avoids assuming every language uses an ASCII space between
 * timed chunks while keeping existing providers that only expose bare words
 * usable through a conservative fallback.
 */
object LyricWordLayout {

    data class Layout(
        val prefixes: List<String>,
        val suffix: String
    )

    fun layout(line: LyricLine): Layout {
        val words = line.words
        if (words.isEmpty()) return Layout(emptyList(), line.text)

        val prefixes = ArrayList<String>(words.size)
        var cursor = 0

        for (word in words) {
            val index = line.text.indexOf(word.text, startIndex = cursor)
            if (index < cursor) return fallback(line)

            prefixes += line.text.substring(cursor, index)
            cursor = index + word.text.length
        }

        return Layout(
            prefixes = prefixes,
            suffix = line.text.substring(cursor)
        )
    }

    fun karaokeText(
        line: LyricLine,
        activeWordIndex: Int,
        openMarker: String = "【",
        closeMarker: String = "】"
    ): String {
        val words = line.words
        if (words.isEmpty() || activeWordIndex !in words.indices) return line.text

        val layout = layout(line)
        return buildString {
            words.forEachIndexed { index, word ->
                append(layout.prefixes.getOrElse(index) { "" })
                if (index == activeWordIndex) append(openMarker)
                append(word.text)
                if (index == activeWordIndex) append(closeMarker)
            }
            append(layout.suffix)
        }
    }

    private fun fallback(line: LyricLine): Layout {
        val separator = if (line.text.any(Char::isWhitespace)) " " else ""
        return Layout(
            prefixes = line.words.indices.map { index ->
                if (index == 0) "" else separator
            },
            suffix = ""
        )
    }
}
