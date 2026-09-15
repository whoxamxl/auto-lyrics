package com.autolyrics.lyrics

import java.util.Locale

object MetadataCleaner {

    private val QUALITY_PATTERN = Regex(
        """\b(lossless|hi-?res|hires|flac|hi-?fi|hifi|dolby\s*atmos|spatial\s*audio|atmos|mqa)\b""",
        RegexOption.IGNORE_CASE
    )
    private val BITRATE_PATTERN = Regex("""\b\d{2,3}\s*kbps\b""", RegexOption.IGNORE_CASE)
    private val BIT_DEPTH_PATTERN = Regex("""\b\d{2}-?bit\b""", RegexOption.IGNORE_CASE)
    private val SAMPLE_RATE_PATTERN = Regex("""\b\d{2,3}(\.\d)?\s*khz\b""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE = Regex("""\s{2,}""")

    private val TITLE_PRESENTATION_GROUP = Regex(
        """\s*[\(\[].*?\b(official|video|lyric|lyrics|audio|visualizer)\b.*?[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val ALBUM_EDITION_GROUP = Regex(
        """\s*[\(\[].*?\b(deluxe|remaster(?:ed)?|expanded|bonus|anniversary|edition|explicit)\b.*?[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val RECORDING_VERSION_PATTERN = Regex(
        """\b(live|acoustic|remix(?:ed)?|remaster(?:ed)?|instrumental|edit(?:ed)?|extended|demo)\b|ライブ|アコースティック|リミックス|リマスター|インストゥルメンタル|インスト|エディット|エクステンデッド|デモ""",
        RegexOption.IGNORE_CASE
    )

    fun cleanArtist(raw: String): String {
        val primary = raw.split(Regex("""\s*[•·|]\s*""")).first().trim()
        val noDash = primary.split(Regex("""\s+[-–—]\s+""")).first().trim()
        val cleaned = removeQualityTags(noDash)
        return cleaned.ifBlank { raw.split(Regex("""\s*[•·|]\s*""")).first().trim() }
    }

    fun cleanTitle(raw: String): String {
        var s = raw.trim()
        // Remove presentation/platform labels while preserving recording-version
        // evidence such as Live or Remastered when it shares the same bracket.
        s = TITLE_PRESENTATION_GROUP.replace(s, ::preserveVersionMarkers)
        s = removeQualityTags(s)
        return s.trim().ifBlank { raw.trim() }
    }

    fun cleanAlbum(raw: String): String {
        var s = raw.trim()
        s = removeQualityTags(s)
        // Edition noise should not dominate album matching. Preserve only version
        // markers that the album-context parser independently recognizes as an
        // explicit version/edition context; incidental words such as "Live" in an
        // ordinary subtitle must not be synthesized into recording-version evidence.
        s = ALBUM_EDITION_GROUP.replace(s, ::preserveExplicitAlbumVersionMarkers)
        return s.trim().ifBlank { raw.trim() }
    }

    private fun preserveVersionMarkers(match: MatchResult): String {
        val markers = RECORDING_VERSION_PATTERN.findAll(match.value)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
        return if (markers.isEmpty()) "" else " (${markers.joinToString(" ")})"
    }

    private fun preserveExplicitAlbumVersionMarkers(match: MatchResult): String {
        val explicitQualifiers = extractAlbumVersionQualifiers(match.value)
        if (explicitQualifiers.isEmpty()) return ""

        val markers = RECORDING_VERSION_PATTERN.findAll(match.value)
            .map { it.value.trim() }
            .filter { marker ->
                LrcLibClient.extractVersionQualifiers(marker)
                    .any { qualifier -> qualifier in explicitQualifiers }
            }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
        return if (markers.isEmpty()) "" else " (${markers.joinToString(" ")})"
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
