package com.autolyrics.lyrics

import com.autolyrics.BuildConfig
import com.autolyrics.model.LyricLine
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal PetitLyrics provider client.
 *
 * This integration intentionally supports the type-3 word-sync XML payload only.
 * Auto Lyrics consumes the first word start time of each line as a line-level
 * timestamp; word-level karaoke timing is not imported.
 *
 * Type-2 binary line-sync payload decoding is deliberately not implemented here.
 */
object PetitLyricsClient {

    private const val ENDPOINT = "https://on.petitlyrics.com/api/GetPetitLyricsData.php"
    private const val SDK_VERSION = "1.3.4"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    internal data class PetitLyricsResult(
        val lyricsType: Int,
        val lines: List<LyricLine>
    )

    val isConfigured: Boolean
        get() = BuildConfig.PETITLYRICS_USER_ID.isNotBlank() &&
            BuildConfig.PETITLYRICS_APP_NAME.isNotBlank() &&
            BuildConfig.PETITLYRICS_PKG_NAME.isNotBlank() &&
            BuildConfig.PETITLYRICS_CLIENT_APP_ID.isNotBlank()

    fun getSyncedLyrics(
        album: String,
        artist: String,
        title: String
    ): PetitLyricsResult? {
        if (!isConfigured || title.isBlank()) return null

        val body = FormBody.Builder()
            .add("lyricsType", "3")
            .add("sdkVer", SDK_VERSION)
            .add("userId", BuildConfig.PETITLYRICS_USER_ID)
            .add("appName", BuildConfig.PETITLYRICS_APP_NAME)
            .add("pkgName", BuildConfig.PETITLYRICS_PKG_NAME)
            .add("clientAppId", BuildConfig.PETITLYRICS_CLIENT_APP_ID)
            .add("index", "0")
            .add("logFlag", "0")
            .add("verCode", BuildConfig.VERSION_CODE.toString())
            .add("verName", BuildConfig.VERSION_NAME)
            .add("maxcount", "1")
            .add("terminalType", "0")
            .add("key_album", album)
            .add("key_artist", artist)
            .add("key_title", title)
            .build()

        val request = Request.Builder()
            .url(ENDPOINT)
            .post(body)
            .header("User-Agent", "AutoLyrics/${BuildConfig.VERSION_NAME} (Android)")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val xml = response.body?.string().orEmpty()
                parseApiResponse(xml)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseApiResponse(xml: String): PetitLyricsResult? {
        if (xml.isBlank()) return null

        val document = parseXml(xml) ?: return null
        val songs = document.getElementsByTagName("song")
        if (songs.length == 0) return null

        val song = songs.item(0) as? Element ?: return null
        val lyricsType = childText(song, "lyricsType")?.toIntOrNull() ?: return null
        val lyricsData = childText(song, "lyricsData")?.takeIf { it.isNotBlank() } ?: return null

        // The reference implementation can also decode type-2 binary line-sync
        // data. This Android provider intentionally imports only the type-3 XML
        // form and falls back to LRCLIB for unsupported response types.
        if (lyricsType != 3) return null

        val decoded = try {
            Base64.getMimeDecoder().decode(lyricsData)
        } catch (_: IllegalArgumentException) {
            return null
        }

        val payload = decoded.toString(Charsets.UTF_8)
        val lines = parseWordSyncPayload(payload)
        if (lines.isEmpty()) return null

        return PetitLyricsResult(lyricsType = lyricsType, lines = lines)
    }

    internal fun parseWordSyncPayload(xml: String): List<LyricLine> {
        val document = parseXml(xml) ?: return emptyList()
        val lineNodes = document.getElementsByTagName("line")
        val lines = ArrayList<LyricLine>(lineNodes.length)

        for (i in 0 until lineNodes.length) {
            val line = lineNodes.item(i) as? Element ?: continue
            val wordNodes = line.getElementsByTagName("word")
            if (wordNodes.length == 0) continue

            val firstWord = wordNodes.item(0) as? Element ?: continue
            val startMs = childText(firstWord, "starttime")?.toLongOrNull() ?: continue
            val text = childText(line, "linestring").orEmpty().ifBlank { "♪" }

            lines += LyricLine(
                timeMs = startMs.coerceAtLeast(0L),
                text = text
            )
        }

        return lines
            .sortedBy { it.timeMs }
            .distinctBy { it.timeMs to it.text }
    }

    private fun parseXml(xml: String) = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newDocumentBuilder().parse(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )
    } catch (_: Exception) {
        null
    }

    private fun childText(parent: Element, tagName: String): String? {
        val nodes = parent.getElementsByTagName(tagName)
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent?.trim()
    }
}
