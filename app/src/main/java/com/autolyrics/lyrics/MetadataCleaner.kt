package com.autolyrics.lyrics

object MetadataCleaner {

    private val QUALITY_PATTERN = Regex(
        """\b(lossless|hi-?res|hires|flac|hi-?fi|hifi|dolby\s*atmos|spatial\s*audio|atmos|mqa)\b""",
        RegexOption.IGNORE_CASE
    )
    private val BITRATE_PATTERN = Regex("""\b\d{2,3}\s*kbps\b""", RegexOption.IGNORE_CASE)
    private val BIT_DEPTH_PATTERN = Regex("""\b\d{2}-?bit\b""", RegexOption.IGNORE_CASE)
    private val SAMPLE_RATE_PATTERN = Regex("""\b\d{2,3}(\.\d)?\s*khz\b""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE = Regex("""\s{2,}""")

    fun cleanArtist(raw: String): String {
        val primary = raw.split(Regex("""\s*[•·|]\s*""")).first().trim()
        val noDash = primary.split(Regex("""\s+[-–—]\s+""")).first().trim()
        val cleaned = removeQualityTags(noDash)
        return cleaned.ifBlank { raw.split(Regex("""\s*[•·|]\s*""")).first().trim() }
    }

    fun cleanTitle(raw: String): String {
        var s = raw.trim()
        // Remove presentation/platform labels, but keep recording-version labels
        // such as Live, Acoustic, Remix and Remaster. Those can identify a
        // genuinely different recording with different timing.
        s = s.replace(
            Regex("""\s*[\(\[].*?\b(official|video|lyric|lyrics|audio|visualizer)\b.*?[\)\]]""", RegexOption.IGNORE_CASE),
            ""
        )
        s = removeQualityTags(s)
        return s.trim().ifBlank { raw.trim() }
    }

    fun cleanAlbum(raw: String): String {
        var s = raw.trim()
        s = removeQualityTags(s)
        s = s.replace(
            Regex("""\s*[\(\[].*?\b(deluxe|remaster|expanded|bonus|anniversary|edition|explicit)\b.*?[\)\]]""", RegexOption.IGNORE_CASE),
            ""
        )
        return s.trim().ifBlank { raw.trim() }
    }

    private fun removeQualityTags(s: String): String {
        return s
            .replace(QUALITY_PATTERN, "")
            .replace(BITRATE_PATTERN, "")
            .replace(BIT_DEPTH_PATTERN, "")
            .replace(SAMPLE_RATE_PATTERN, "")
            .replace(MULTI_SPACE, " ")
            .trim()
            .trimEnd('-', '–', '—', ',', ';', ':', '•', '·', '(', '[')
            .trimStart('-', '–', '—', ',', ';', ':', '•', '·', ')', ']')
            .trim()
    }
}
