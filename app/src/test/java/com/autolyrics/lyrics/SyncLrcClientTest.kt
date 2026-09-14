package com.autolyrics.lyrics

import com.autolyrics.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLrcClientTest {

    private val track = TrackInfo(
        title = "Provider",
        artist = "Example Artist",
        album = "Example Album",
        durationMs = 180_000L
    )

    @Test
    fun acceptsCurrentPublicKaraokeFieldResponse() {
        val json = """
            {
              "id": "abc",
              "track": "Provider",
              "artist": "Example Artist",
              "album": "Example Album",
              "duration": 180,
              "instrumental": false,
              "karaoke": "[00:01.00]<00:01.00>Pro<00:01.10>vi<00:01.20>der",
              "synced": "[00:01.00]Provider",
              "plain": "Provider"
            }
        """.trimIndent()

        val result = SyncLrcClient.parseApiResponse(json, track)

        assertEquals("Provider", result?.matchedTitle)
        assertEquals("Example Artist", result?.matchedArtist)
        assertEquals(180.0, result?.matchedDurationSec ?: 0.0, 0.001)
        assertEquals("Provider", result?.lines?.single()?.text)
        assertEquals(listOf("Pro", "vi", "der"), result?.lines?.single()?.words?.map { it.text })
    }

    @Test
    fun acceptsLegacyTypeSpecificKaraokeResponseForCompatibility() {
        val json = """
            {
              "track": "Provider",
              "artist": "Example Artist",
              "type": "karaoke",
              "lyrics": "[00:01.00]<00:01.00>Provider"
            }
        """.trimIndent()

        val result = SyncLrcClient.parseApiResponse(json, track)

        assertEquals("Provider", result?.lines?.single()?.text)
    }

    @Test
    fun rejectsCurrentResponseWithoutKaraokePayload() {
        val json = """
            {
              "track": "Provider",
              "artist": "Example Artist",
              "synced": "[00:01.00]Provider",
              "plain": "Provider"
            }
        """.trimIndent()

        assertNull(SyncLrcClient.parseApiResponse(json, track))
    }

    @Test
    fun rejectsLegacySyncedFallbackFromKaraokeRequest() {
        val json = """
            {
              "track": "Provider",
              "artist": "Example Artist",
              "type": "synced",
              "lyrics": "[00:01.00]Provider"
            }
        """.trimIndent()

        assertNull(SyncLrcClient.parseApiResponse(json, track))
    }

    @Test
    fun rejectsLegacyPlainFallbackFromKaraokeRequest() {
        val json = """
            {
              "track": "Provider",
              "artist": "Example Artist",
              "type": "plain",
              "lyrics": "Provider"
            }
        """.trimIndent()

        assertNull(SyncLrcClient.parseApiResponse(json, track))
    }

    @Test
    fun rejectsInstrumentalResponse() {
        val json = """
            {
              "track": "Provider",
              "artist": "Example Artist",
              "instrumental": true,
              "karaoke": "[00:01.00]<00:01.00>Provider"
            }
        """.trimIndent()

        assertNull(SyncLrcClient.parseApiResponse(json, track))
    }

    @Test
    fun preservesJapaneseFineGrainedTimingForDisplayLayer() {
        val json = """
            {
              "track": "君を忘れない",
              "artist": "Example Artist",
              "karaoke": "[00:01.00]<00:01.00>君<00:01.10>を<00:01.20>忘<00:01.30>れ<00:01.40>な<00:01.50>い"
            }
        """.trimIndent()

        val result = SyncLrcClient.parseApiResponse(json)
        val line = result?.lines?.single()

        assertEquals("君を忘れない", line?.text)
        assertEquals(6, line?.words?.size)
        assertTrue(line?.words?.all { it.text.length == 1 } == true)
    }
}
