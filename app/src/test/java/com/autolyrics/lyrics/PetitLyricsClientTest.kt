package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class PetitLyricsClientTest {

    @Test
    fun parsesType3WordSyncAsLineTimestamps() {
        val payload = """
            <wsy>
              <line>
                <linestring>Alpha line</linestring>
                <word>
                  <starttime>1200</starttime>
                  <endtime>1500</endtime>
                  <wordstring>Alpha</wordstring>
                </word>
              </line>
              <line>
                <linestring>Beta line</linestring>
                <word>
                  <starttime>3400</starttime>
                  <endtime>3700</endtime>
                  <wordstring>Beta</wordstring>
                </word>
              </line>
            </wsy>
        """.trimIndent()

        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val response = """
            <result>
              <songs>
                <song>
                  <lyricsType>3</lyricsType>
                  <lyricsData>$encoded</lyricsData>
                </song>
              </songs>
            </result>
        """.trimIndent()

        val result = PetitLyricsClient.parseApiResponse(response)

        assertEquals(3, result?.lyricsType)
        assertEquals(2, result?.lines?.size)
        assertEquals(1200L, result?.lines?.get(0)?.timeMs)
        assertEquals("Alpha line", result?.lines?.get(0)?.text)
        assertEquals(3400L, result?.lines?.get(1)?.timeMs)
        assertEquals("Beta line", result?.lines?.get(1)?.text)
    }

    @Test
    fun blankLineIsPreservedAsMusicMarker() {
        val payload = """
            <wsy>
              <line>
                <linestring></linestring>
                <word><starttime>5000</starttime></word>
              </line>
            </wsy>
        """.trimIndent()

        val lines = PetitLyricsClient.parseWordSyncPayload(payload)

        assertEquals(1, lines.size)
        assertEquals(5000L, lines[0].timeMs)
        assertEquals("♪", lines[0].text)
    }

    @Test
    fun type2BinaryPayloadIsNotImported() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val response = """
            <result>
              <songs>
                <song>
                  <lyricsType>2</lyricsType>
                  <lyricsData>$encoded</lyricsData>
                </song>
              </songs>
            </result>
        """.trimIndent()

        assertNull(PetitLyricsClient.parseApiResponse(response))
    }

    @Test
    fun malformedResponseReturnsNull() {
        assertNull(PetitLyricsClient.parseApiResponse("<not-closed"))
    }
}
