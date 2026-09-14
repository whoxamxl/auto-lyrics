package com.autolyrics.util

import com.autolyrics.model.LyricLine
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * Reconstructs separators around timed lyric tokens from the provider's original
 * line text and maps fine-grained provider timing tokens onto human-readable
 * display units for karaoke highlighting.
 *
 * Provider timing is deliberately left untouched in [LyricLine.words]. A source
 * may expose a whole word, a syllable, or even a single character per timed token.
 * Rendering therefore finds the lexical range that contains the active timing
 * token and highlights that range instead of assuming one token equals one word.
 */
object LyricWordLayout {

    data class Layout(
        val prefixes: List<String>,
        val suffix: String
    )

    internal data class DisplayRange(
        val start: Int,
        val end: Int
    )

    private data class TokenSpan(
        val start: Int,
        val end: Int
    )

    private val JAPANESE_SCRIPT = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    private val HAN_SCRIPT = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    private val HIRAGANA_ONLY = Regex("[\\u3040-\\u309fー]+")

    fun layout(line: LyricLine): Layout {
        val words = line.words
        if (words.isEmpty()) return Layout(emptyList(), line.text)

        val prefixes = ArrayList<String>(words.size)
        var cursor = 0

        for (word in words) {
            val index = line.text.indexOf(word.text, startIndex = cursor)
            if (index < 0) return fallback(line)

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

        val displayRange = displayRangeForToken(line, activeWordIndex)
        if (displayRange != null) {
            return buildString(line.text.length + openMarker.length + closeMarker.length) {
                append(line.text, 0, displayRange.start)
                append(openMarker)
                append(line.text, displayRange.start, displayRange.end)
                append(closeMarker)
                append(line.text, displayRange.end, line.text.length)
            }
        }

        // Conservative compatibility fallback for provider payloads whose token
        // strings cannot be aligned with the provider's full line text.
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

    /**
     * Returns the human-readable character range that should be highlighted for
     * a provider timing token. The range is expressed against [LyricLine.text]
     * with an exclusive [DisplayRange.end].
     *
     * This is internal so renderers can share the exact same grouping logic as
     * [karaokeText] without exposing timing/display coupling as public API.
     */
    internal fun displayRangeForToken(line: LyricLine, activeTokenIndex: Int): DisplayRange? {
        val ranges = lexicalRanges(line.text)
        if (ranges.isEmpty() || activeTokenIndex !in line.words.indices) return null

        val tokenSpans = locateTokensBestEffort(line)
        val token = tokenSpans[activeTokenIndex]
        if (token == null) {
            return fallbackDisplayRange(line, activeTokenIndex, ranges, tokenSpans)
        }

        ranges.firstOrNull { range ->
            token.start < range.end && token.end > range.start
        }?.let { return it }

        // Whitespace and punctuation can themselves be timed by some providers.
        // Keep the visible word stable through those tiny bridge tokens by mapping
        // them to the nearest lexical range, preferring the preceding word on ties.
        return ranges.minWithOrNull(
            compareBy<DisplayRange> { distance(token, it) }
                .thenBy { if (it.end <= token.start) 0 else 1 }
        )
    }

    private fun locateTokensBestEffort(line: LyricLine): List<TokenSpan?> {
        val spans = MutableList<TokenSpan?>(line.words.size) { null }
        var cursor = 0

        line.words.forEachIndexed { index, word ->
            val span = findTokenSpan(line.text, word.text, cursor) ?: return@forEachIndexed
            spans[index] = span
            cursor = span.end
        }

        return spans
    }

    private fun findTokenSpan(text: String, token: String, startIndex: Int): TokenSpan? {
        if (token.isEmpty() || startIndex >= text.length) return null

        val exactIndex = text.indexOf(token, startIndex = startIndex)
        if (exactIndex >= 0) {
            return TokenSpan(exactIndex, exactIndex + token.length)
        }

        val caseInsensitiveIndex = text.indexOf(token, startIndex = startIndex, ignoreCase = true)
        if (caseInsensitiveIndex >= 0) {
            return TokenSpan(caseInsensitiveIndex, caseInsensitiveIndex + token.length)
        }

        val normalizedToken = normalizeForAlignment(token)
        for (candidateStart in startIndex until text.length) {
            val maxEnd = minOf(
                text.length,
                candidateStart + token.length + NORMALIZATION_SLACK_CHARS
            )
            for (candidateEnd in (candidateStart + 1)..maxEnd) {
                if (normalizeForAlignment(text.substring(candidateStart, candidateEnd)) == normalizedToken) {
                    return TokenSpan(candidateStart, candidateEnd)
                }
            }
        }

        return null
    }

    private fun normalizeForAlignment(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun fallbackDisplayRange(
        line: LyricLine,
        activeTokenIndex: Int,
        ranges: List<DisplayRange>,
        tokenSpans: List<TokenSpan?>
    ): DisplayRange? {
        if (activeTokenIndex !in line.words.indices || ranges.isEmpty()) return null
        if (line.words.size == 1) {
            return DisplayRange(ranges.first().start, ranges.last().end)
        }

        val previousIndex = (activeTokenIndex - 1 downTo 0)
            .firstOrNull { tokenSpans[it] != null }
        val nextIndex = (activeTokenIndex + 1..line.words.lastIndex)
            .firstOrNull { tokenSpans[it] != null }
        val blockStartIndex = (previousIndex ?: -1) + 1
        val blockEndIndex = (nextIndex ?: line.words.size) - 1
        val intervalStart = tokenSpans.getOrNull(previousIndex ?: -1)?.end
            ?: ranges.first().start
        val intervalEnd = tokenSpans.getOrNull(nextIndex ?: tokenSpans.size)?.start
            ?: ranges.last().end

        // Only the unaligned block is estimated. Successfully aligned neighbors
        // remain anchors, so a single case/punctuation/normalization mismatch does
        // not redistribute the surrounding syllable timing across later words.
        val tokenLengths = (blockStartIndex..blockEndIndex).map { index ->
            line.words[index].text.length.coerceAtLeast(1)
        }
        val totalLength = tokenLengths.sum().coerceAtLeast(1)
        val activeOffset = activeTokenIndex - blockStartIndex
        val consumedBefore = tokenLengths.take(activeOffset).sum()
        val tokenMidpoint = consumedBefore + tokenLengths[activeOffset] / 2f
        val fraction = (tokenMidpoint / totalLength).coerceIn(0f, 1f)
        val start = intervalStart.toFloat()
        val end = intervalEnd.coerceAtLeast(intervalStart).toFloat()
        val target = start + (end - start) * fraction

        return ranges.minWithOrNull(
            compareBy<DisplayRange> { pointDistance(target, it) }
                .thenBy { if (it.end.toFloat() <= target) 0 else 1 }
        )
    }

    private fun lexicalRanges(text: String): List<DisplayRange> {
        if (text.isBlank()) return emptyList()

        val locale = if (JAPANESE_SCRIPT.containsMatchIn(text)) Locale.JAPANESE else Locale.ROOT
        val iterator = BreakIterator.getWordInstance(locale)
        iterator.setText(text)

        val rawRanges = ArrayList<DisplayRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val segment = text.substring(start, end)
            if (segment.any { it.isLetterOrDigit() } || JAPANESE_SCRIPT.containsMatchIn(segment)) {
                rawRanges += DisplayRange(start, end)
            }
            start = end
            end = iterator.next()
        }

        if (rawRanges.size < 2 || !JAPANESE_SCRIPT.containsMatchIn(text)) return rawRanges

        // Japanese dictionary boundaries can still expose a stem and its okurigana
        // as separate ranges (e.g. 忘 + れない). Merge an adjacent hiragana suffix
        // into a preceding kanji-containing range. This is intentionally a display
        // heuristic only; the original per-token timestamps remain available.
        val merged = ArrayList<DisplayRange>(rawRanges.size)
        for (range in rawRanges) {
            val currentText = text.substring(range.start, range.end)
            val previous = merged.lastOrNull()
            if (previous != null && previous.end == range.start) {
                val previousText = text.substring(previous.start, previous.end)
                if (HAN_SCRIPT.containsMatchIn(previousText) && HIRAGANA_ONLY.matches(currentText)) {
                    merged[merged.lastIndex] = DisplayRange(previous.start, range.end)
                    continue
                }
            }
            merged += range
        }
        return merged
    }

    private fun distance(token: TokenSpan, range: DisplayRange): Int {
        return when {
            token.end <= range.start -> range.start - token.end
            range.end <= token.start -> token.start - range.end
            else -> 0
        }
    }

    private fun pointDistance(point: Float, range: DisplayRange): Float {
        return when {
            point < range.start -> range.start - point
            point > range.end -> point - range.end
            else -> 0f
        }
    }

    private fun fallback(line: LyricLine): Layout {
        val separator = if (line.text.any { it.isWhitespace() }) " " else ""
        return Layout(
            prefixes = line.words.indices.map { index ->
                if (index == 0) "" else separator
            },
            suffix = ""
        )
    }

    private const val NORMALIZATION_SLACK_CHARS = 4
}
