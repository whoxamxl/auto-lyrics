package com.autolyrics.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import com.autolyrics.model.LyricLine
import com.autolyrics.util.LyricWordLayout
import kotlin.math.roundToInt

internal object PhoneKaraokeSweep {

    data class Segment(
        val start: Int,
        val end: Int,
        val startTimeMs: Long,
        val endTimeMs: Long
    )

    fun segmentAtPosition(line: LyricLine, positionMs: Long): Segment? {
        if (line.words.isEmpty()) return null

        var latestToken = -1
        for (index in line.words.indices) {
            if (line.words[index].timeMs <= positionMs) {
                latestToken = index
            } else {
                break
            }
        }
        if (latestToken < 0) return null

        val activeRange = LyricWordLayout.displayRangeForToken(line, latestToken) ?: return null

        var groupFirst = latestToken
        while (groupFirst > 0 && sameDisplayRange(line, groupFirst - 1, activeRange.start, activeRange.end)) {
            groupFirst--
        }

        var groupLast = latestToken
        while (
            groupLast + 1 < line.words.size &&
            sameDisplayRange(line, groupLast + 1, activeRange.start, activeRange.end)
        ) {
            groupLast++
        }

        val groupStartMs = line.words[groupFirst].timeMs
        val groupEndMs = line.words[groupLast].endTimeMs
            ?: line.words.getOrNull(groupLast + 1)?.timeMs
            ?: (groupStartMs + LAST_GROUP_MS)

        return Segment(
            start = activeRange.start,
            end = activeRange.end,
            startTimeMs = groupStartMs,
            endTimeMs = groupEndMs.coerceAtLeast(groupStartMs + 1L)
        )
    }

    private fun sameDisplayRange(
        line: LyricLine,
        tokenIndex: Int,
        start: Int,
        end: Int
    ): Boolean {
        val range = LyricWordLayout.displayRangeForToken(line, tokenIndex) ?: return false
        return range.start == start && range.end == end
    }

    private const val LAST_GROUP_MS = 650L
}

internal class KaraokeSweepSpan(
    private val segment: PhoneKaraokeSweep.Segment,
    private val pendingColor: Int,
    private val completedColor: Int,
    private val positionProvider: () -> Long
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = paint.measureText(text, start, end).roundToInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val originalColor = paint.color
        val rendered = text.subSequence(start, end).toString()
        val width = paint.measureText(rendered)

        paint.color = pendingColor
        canvas.drawText(rendered, x, y.toFloat(), paint)

        val progress = ((positionProvider() - segment.startTimeMs).toFloat() /
            (segment.endTimeMs - segment.startTimeMs).coerceAtLeast(1L))
            .coerceIn(0f, 1f)

        if (progress > 0f && width > 0f) {
            canvas.save()
            canvas.clipRect(x, top.toFloat(), x + width * progress, bottom.toFloat())
            paint.color = completedColor
            canvas.drawText(rendered, x, y.toFloat(), paint)
            canvas.restore()
        }

        paint.color = originalColor
    }
}
