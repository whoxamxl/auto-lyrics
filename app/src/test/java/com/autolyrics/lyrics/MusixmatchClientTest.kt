package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusixmatchClientTest {

    @Test
    fun parsesTrackSearchMetadata() {
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "track_list": [
                    {
                      "track": {
                        "track_id": 123,
                        "commontrack_id": 456,
                        "track_name": "Hey Jude",
                        "artist_name": "The Beatles",
                        "album_name": "1",
                        "track_length": 431,
                        "has_richsync": 1,
                        "has_lyrics": 1
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = MusixmatchClient.parseSearchResponse(json)

        assertEquals(1, result.size)
        assertEquals(123L, result[0].trackId)
        assertEquals(456L, result[0].commonTrackId)
        assertEquals("Hey Jude", result[0].title)
        assertEquals("The Beatles", result[0].artist)
        assertEquals(431.0, result[0].durationSec ?: 0.0, 0.001)
        assertTrue(result[0].hasRichSync)
        assertTrue(result[0].hasLyrics)
    }

    @Test
    fun parsesRichSyncIntoLineAndWordTiming() {
        val richSyncBody = """[{"ts":1.5,"te":4.0,"x":"Hey Jude","l":[{"c":"Hey","o":0.0},{"c":" ","o":0.3},{"c":"Jude","o":0.5}]}]"""
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "richsync": {
                    "richsync_body": "$richSyncBody"
                  }
                }
              }
            }
        """.trimIndent()

        val lines = MusixmatchClient.parseRichSyncResponse(json)

        assertEquals(1, lines.size)
        assertEquals(1_500L, lines[0].timeMs)
        assertEquals("Hey Jude", lines[0].text)
        assertEquals(2, lines[0].words.size)
        assertEquals("Hey", lines[0].words[0].text)
        assertEquals(1_500L, lines[0].words[0].timeMs)
        assertEquals("Jude", lines[0].words[1].text)
        assertEquals(2_000L, lines[0].words[1].timeMs)
    }

    @Test
    fun japaneseRichSyncKeepsExactLineTextWithoutArtificialWordSpaces() {
        val richSyncBody = """[{"ts":2.0,"te":5.0,"x":"君を忘れない","l":[{"c":"君を","o":0.0},{"c":"忘れない","o":0.8}]}]"""
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {"richsync": {"richsync_body": "$richSyncBody"}}
              }
            }
        """.trimIndent()

        val lines = MusixmatchClient.parseRichSyncResponse(json)

        assertEquals(1, lines.size)
        assertEquals("君を忘れない", lines[0].text)
        assertTrue(lines[0].words.isEmpty())
    }

    @Test
    fun stripsMusixmatchPlainLyricsFooter() {
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "lyrics": {
                    "lyrics_body": "Line one\nLine two\n******* This Lyrics is NOT for Commercial use *******"
                  }
                }
              }
            }
        """.trimIndent()

        val lines = MusixmatchClient.parsePlainLyricsResponse(json)

        assertEquals(listOf("Line one", "Line two"), lines.map { it.text })
        assertFalse(lines.any { it.text.contains("Commercial use") })
    }
}
