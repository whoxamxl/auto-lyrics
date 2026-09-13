package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun decodesType2LineSyncWithPlainTextCompanion() {
        val protectionKey = 0x2345
        val encrypted = buildLineSyncPayload(
            protectionKey = protectionKey,
            timesCentiseconds = intArrayOf(123, 456)
        )
        val syncBase64 = Base64.getEncoder().encodeToString(encrypted)
        val plainBase64 = Base64.getEncoder()
            .encodeToString("First line\nSecond line".toByteArray(Charsets.UTF_8))

        val lines = PetitLyricsClient.decodeLineSyncPayload(syncBase64, plainBase64)

        assertEquals(2, lines.size)
        assertEquals(1230L, lines[0].timeMs)
        assertEquals("First line", lines[0].text)
        assertEquals(4560L, lines[1].timeMs)
        assertEquals("Second line", lines[1].text)
    }

    @Test
    fun lineSyncDecoderHandlesCentisecondCounterRollover() {
        val encrypted = buildLineSyncPayload(
            protectionKey = 0x0102,
            timesCentiseconds = intArrayOf(65_000, 66_000)
        )
        val syncBase64 = Base64.getEncoder().encodeToString(encrypted)
        val plainBase64 = Base64.getEncoder()
            .encodeToString("Before rollover\nAfter rollover".toByteArray(Charsets.UTF_8))

        val lines = PetitLyricsClient.decodeLineSyncPayload(syncBase64, plainBase64)

        assertEquals(650_000L, lines[0].timeMs)
        assertEquals(660_000L, lines[1].timeMs)
    }

    @Test
    fun exactJapaneseTitleAllowsRomanizedArtistMismatch() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val response = """
            <result>
              <songs>
                <song>
                  <lyricsId>70129</lyricsId>
                  <title>巡恋歌</title>
                  <artist>長渕 剛</artist>
                  <album>風は南から</album>
                  <lyricsType>2</lyricsType>
                  <lyricsData>$encoded</lyricsData>
                </song>
              </songs>
            </result>
        """.trimIndent()

        val candidates = PetitLyricsClient.parseCandidates(response)
        val selected = PetitLyricsClient.selectBestCandidate(
            candidates = candidates,
            requestedTitle = "巡恋歌",
            requestedArtist = "Tsuyoshi Nagabuchi",
            requestedAlbum = "風は南から"
        )

        assertNotNull(selected)
        assertEquals("70129", selected?.lyricsId)
        assertEquals(2, selected?.lyricsType)
    }

    @Test
    fun clearlyDifferentTitleIsRejected() {
        val candidate = PetitLyricsClient.PetitLyricsCandidate(
            lyricsId = "1",
            title = "乾杯",
            artist = "長渕 剛",
            album = "",
            lyricsType = 2,
            lyricsData = "AA=="
        )

        assertNull(
            PetitLyricsClient.selectBestCandidate(
                candidates = listOf(candidate),
                requestedTitle = "巡恋歌",
                requestedArtist = "Tsuyoshi Nagabuchi",
                requestedAlbum = ""
            )
        )
    }

    @Test
    fun malformedResponseReturnsNull() {
        assertNull(PetitLyricsClient.parseApiResponse("<not-closed"))
    }

    private fun buildLineSyncPayload(
        protectionKey: Int,
        timesCentiseconds: IntArray
    ): ByteArray {
        val bytes = ByteArray(0xcc + timesCentiseconds.size * 2)
        bytes[0x19] = 0 // protection key permutation disabled for this fixture
        writeUInt16Le(bytes, 0x1a, protectionKey)
        writeUInt32Le(bytes, 0x38, timesCentiseconds.size)
        writeUInt16Le(bytes, 0x42, 64)

        timesCentiseconds.forEachIndexed { index, absoluteCs ->
            val modulo = absoluteCs and 0xffff
            val raw = modulo xor protectionKey
            writeUInt16Le(bytes, 0xcc + index * 2, raw)
        }
        return bytes
    }

    private fun writeUInt16Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun writeUInt32Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}
