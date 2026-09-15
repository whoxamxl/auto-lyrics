package com.autolyrics.lyrics

import java.text.Normalizer
import java.util.Locale

private const val ENGLISH_VERSION_MARKER =
    "(?:live|acoustic|remix(?:ed)?|remaster(?:ed)?|instrumental|edit(?:ed)?|extended|demo)"
private const val JAPANESE_VERSION_MARKER =
    "(?:ライブ|アコースティック|リミックス|リマスター|インストゥルメンタル|インスト|エディット|エクステンデッド|デモ)(?:版|盤|バージョン)?"

private val BRACKETED_ALBUM_CONTEXT = Regex("""[\(\[].*?[\)\]]""")
private val SEPARATOR_ALBUM_CONTEXT = Regex("""\s[-–—:]\s(.+)$""")
private val LIVE_LOCATION_CONTEXT = Regex(
    """^\s*(?:live|ライブ)\s+(?:at|from|in)\b.*$""",
    RegexOption.IGNORE_CASE
)
private val ENGLISH_VERSION_TOKEN = Regex(
    """\b$ENGLISH_VERSION_MARKER\b""",
    RegexOption.IGNORE_CASE
)
private val ENGLISH_CONTEXT_WORD = Regex("""[a-z]+|\d{4}""")
private val JAPANESE_VERSION_CONTEXT = Regex(
    """^\s*$JAPANESE_VERSION_MARKER\s*$"""
)
private val ALLOWED_ENGLISH_CONTEXT_WORDS = setOf(
    "live",
    "acoustic",
    "remix",
    "remixed",
    "remaster",
    "remastered",
    "instrumental",
    "edit",
    "edited",
    "extended",
    "demo",
    "deluxe",
    "expanded",
    "bonus",
    "anniversary",
    "edition",
    "explicit",
    "version",
    "ver",
    "radio",
    "mix",
    "performance",
    "session",
    "concert"
)

/**
 * Extract recording-version evidence from album metadata only when the marker is
 * presented as an explicit edition/version context. Bracketed labels, separator
 * suffixes and whole-album labels all use the same classifier so ordinary names
 * such as "Live Through This", "Album - Live Through This" and "We Live Here"
 * cannot become recording-version evidence merely because they contain `Live`.
 */
internal fun extractAlbumVersionQualifiers(value: String): Set<String> {
    if (value.isBlank()) return emptySet()

    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
    val contexts = buildList {
        BRACKETED_ALBUM_CONTEXT.findAll(normalized).forEach { match ->
            val inner = match.value.substring(1, match.value.length - 1).trim()
            if (isExplicitVersionContext(inner)) add(inner)
        }

        SEPARATOR_ALBUM_CONTEXT.find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(::isExplicitVersionContext)
            ?.let(::add)

        if (isExplicitVersionContext(normalized)) add(normalized)
    }

    return contexts
        .flatMap { LrcLibClient.extractVersionQualifiers(it).toList() }
        .toSet()
}

private fun isExplicitVersionContext(value: String): Boolean {
    val text = value.trim()
    if (text.isBlank()) return false

    if (JAPANESE_VERSION_CONTEXT.matches(text)) return true
    if (LIVE_LOCATION_CONTEXT.matches(text)) return true
    if (!ENGLISH_VERSION_TOKEN.containsMatchIn(text)) return false

    val words = ENGLISH_CONTEXT_WORD.findAll(text.lowercase(Locale.ROOT))
        .map { it.value }
        .toList()
    if (words.isEmpty()) return false

    return words.all { word ->
        word in ALLOWED_ENGLISH_CONTEXT_WORDS ||
            word.toIntOrNull()?.let { it in 1900..2199 } == true
    }
}
