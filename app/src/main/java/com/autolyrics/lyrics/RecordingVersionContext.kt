package com.autolyrics.lyrics

import java.text.Normalizer

private val BRACKETED_ALBUM_VERSION_CONTEXT = Regex("""[\(\[].*?[\)\]]""")
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

/**
 * Extract recording-version evidence from album metadata only when the marker is
 * presented as an explicit edition/version context. This deliberately avoids
 * treating ordinary album names such as "Live Through This" as proof that the
 * currently playing recording is a live version.
 */
internal fun extractAlbumVersionQualifiers(value: String): Set<String> {
    if (value.isBlank()) return emptySet()

    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
    val contexts = buildList {
        BRACKETED_ALBUM_VERSION_CONTEXT.findAll(normalized).forEach { add(it.value) }
        SUFFIX_ALBUM_VERSION_CONTEXT.find(normalized)?.let { add(it.value) }
        WHOLE_ALBUM_VERSION_CONTEXT.find(normalized)?.let { add(it.value) }
        LIVE_LOCATION_ALBUM_CONTEXT.find(normalized)?.let { add(it.value) }
    }

    return contexts
        .flatMap { LrcLibClient.extractVersionQualifiers(it).toList() }
        .toSet()
}
