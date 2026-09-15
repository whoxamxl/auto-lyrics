package com.autolyrics.lyrics

import java.text.Normalizer
import java.util.Locale

private const val ENGLISH_VERSION_MARKER =
    "(?:live|acoustic|remix(?:ed)?|remaster(?:ed)?|instrumental|edit(?:ed)?|extended|demo)"
private const val JAPANESE_VERSION_MARKER =
    "(?:ライブ|アコースティック|リミックス|リマスター|インストゥルメンタル|インスト|エディット|エクステンデッド|デモ)(?:版|盤|バージョン)?"

private val BRACKETED_ALBUM_CONTEXT = Regex("""[\(\[].*?[\)\]]""")
private val VERSION_SEPARATOR = Regex("""\s[-–—]\s|:\s*""")
private val LIVE_LOCATION_CONTEXT = Regex(
    """^\s*(?:live|ライブ)\s+(?:at|from|in)\b.*$""",
    RegexOption.IGNORE_CASE
)
private val ENGLISH_VERSION_TOKEN = Regex(
    """\b$ENGLISH_VERSION_MARKER\b""",
    RegexOption.IGNORE_CASE
)
private val CONTEXT_TOKEN = Regex("""[\p{L}\p{N}]+""")
private val ENGLISH_ORDINAL_TOKEN = Regex("""\d+(?:st|nd|rd|th)""", RegexOption.IGNORE_CASE)
private val JAPANESE_VERSION_CONTEXT = Regex(
    """^\s*(?:(?:\d{4}年?|\d+\s*周年)\s*)?$JAPANESE_VERSION_MARKER(?:\s*(?:記念|エディション))?\s*$"""
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
 * presented as an explicit edition/version context. Bracketed labels and each
 * suffix segment after a conventional separator use the same classifier so
 * ordinary names such as "Live Through This" cannot become version evidence.
 */
internal fun extractAlbumVersionQualifiers(value: String): Set<String> {
    if (value.isBlank()) return emptySet()

    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
    val contexts = buildList {
        BRACKETED_ALBUM_CONTEXT.findAll(normalized).forEach { match ->
            val inner = match.value.substring(1, match.value.length - 1).trim()
            if (isExplicitVersionContext(inner)) add(inner)
        }

        // Inspect every suffix segment independently. This makes the final label in
        // "Album: Subtitle: Live" visible without letting the ordinary album-name
        // prefix participate as version evidence.
        VERSION_SEPARATOR.split(normalized)
            .drop(1)
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(::isExplicitVersionContext)
            .forEach(::add)

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

    // Inspect every Unicode letter/number token, not only ASCII words. This keeps
    // mixed prose such as "LIVE・ドア" from silently dropping the non-Latin part
    // and being misclassified as an explicit live-version label.
    val tokens = CONTEXT_TOKEN.findAll(text.lowercase(Locale.ROOT))
        .map { it.value }
        .toList()
    if (tokens.isEmpty()) return false

    val anniversaryContext = "anniversary" in tokens
    return tokens.all { token ->
        token in ALLOWED_ENGLISH_CONTEXT_WORDS ||
            token.toIntOrNull()?.let { it in 1900..2199 } == true ||
            (anniversaryContext && ENGLISH_ORDINAL_TOKEN.matches(token))
    }
}
