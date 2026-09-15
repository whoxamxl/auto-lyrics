package com.autolyrics.lyrics

import java.text.Normalizer
import java.util.Locale

private val BRACKETED_ALBUM_CONTEXT = Regex("""[\(\[].*?[\)\]]""")
private val SUFFIX_ALBUM_VERSION_CONTEXT = Regex(
    """\s[-–—:]\s(?:live|acoustic|remix(?:ed)?|remaster(?:ed)?|instrumental|edit(?:ed)?|extended|demo|ライブ|アコースティック|リミックス|リマスター|インストゥルメンタル|インスト|エディット|エクステンデッド|デモ)\b.*$""",
    RegexOption.IGNORE_CASE
)
private val WHOLE_ALBUM_VERSION_CONTEXT = Regex(
    """^\s*(?:live|acoustic|remix(?:ed)?|remaster(?:ed)?|instrumental|edit(?:ed)?|extended|demo|ライブ|アコースティック|リミックス|リマスター|インストゥルメンタル|インスト|エディット|エクステンデッド|デモ)\s*$""",
    RegexOption.IGNORE_CASE
)
private val LIVE_LOCATION_ALBUM_CONTEXT = Regex(
    """^\s*(?:live|ライブ)\s+(?:at|from|in)\b.*$""",
    RegexOption.IGNORE_CASE
)
private val ENGLISH_VERSION_TOKEN = Regex(
    """\b(?:live|acoustic|remix(?:ed)?|remaster(?:ed)?|instrumental|edit(?:ed)?|extended|demo)\b""",
    RegexOption.IGNORE_CASE
)
private val ENGLISH_CONTEXT_WORD = Regex("""[a-z]+|\d{4}""")
private val JAPANESE_BRACKET_VERSION_CONTEXT = Regex(
    """^\s*(?:ライブ|アコースティック|リミックス|リマスター|インストゥルメンタル|インスト|エディット|エクステンデッド|デモ)(?:版|盤|バージョン)?\s*$"""
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
    "mix"
)

/**
 * Extract recording-version evidence from album metadata only when the marker is
 * presented as an explicit edition/version context. This deliberately avoids
 * treating ordinary album names such as "Live Through This" or bracketed
 * subtitles such as "Album (We Live Here)" as proof that the currently playing
 * recording is a live version.
 */
internal fun extractAlbumVersionQualifiers(value: String): Set<String> {
    if (value.isBlank()) return emptySet()

    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
    val contexts = buildList {
        BRACKETED_ALBUM_CONTEXT.findAll(normalized)
            .map { it.value }
            .filter(::isExplicitBracketVersionContext)
            .forEach(::add)
        SUFFIX_ALBUM_VERSION_CONTEXT.find(normalized)?.let { add(it.value) }
        WHOLE_ALBUM_VERSION_CONTEXT.find(normalized)?.let { add(it.value) }
        LIVE_LOCATION_ALBUM_CONTEXT.find(normalized)?.let { add(it.value) }
    }

    return contexts
        .flatMap { LrcLibClient.extractVersionQualifiers(it).toList() }
        .toSet()
}

private fun isExplicitBracketVersionContext(bracketed: String): Boolean {
    if (bracketed.length < 2) return false
    val inner = bracketed.substring(1, bracketed.length - 1).trim()
    if (inner.isBlank()) return false

    if (JAPANESE_BRACKET_VERSION_CONTEXT.matches(inner)) return true
    if (LIVE_LOCATION_ALBUM_CONTEXT.matches(inner)) return true
    if (!ENGLISH_VERSION_TOKEN.containsMatchIn(inner)) return false

    val words = ENGLISH_CONTEXT_WORD.findAll(inner.lowercase(Locale.ROOT))
        .map { it.value }
        .toList()
    if (words.isEmpty()) return false

    return words.all { word ->
        word in ALLOWED_ENGLISH_CONTEXT_WORDS ||
            word.toIntOrNull()?.let { it in 1900..2199 } == true
    }
}
