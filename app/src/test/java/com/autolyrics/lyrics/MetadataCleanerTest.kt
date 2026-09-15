package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataCleanerTest {

    @Test
    fun removesPurePresentationLabels() {
        assertEquals("Song", MetadataCleaner.cleanTitle("Song (Official Video)"))
    }

    @Test
    fun preservesLiveMarkerInsidePresentationLabel() {
        assertEquals("Song (Live)", MetadataCleaner.cleanTitle("Song (Official Live Video)"))
    }

    @Test
    fun preservesRemasterMarkerInsideAlbumEditionLabel() {
        assertEquals(
            "Album (Remastered)",
            MetadataCleaner.cleanAlbum("Album (Deluxe Remastered Edition)")
        )
    }

    @Test
    fun doesNotPromoteIncidentalLiveInsideAlbumEditionLabel() {
        assertEquals(
            "Album",
            MetadataCleaner.cleanAlbum("Album (We Live Here — Deluxe Edition)")
        )
    }

    @Test
    fun preservesOnlyExplicitVersionMarkerInsideMixedAlbumEditionLabel() {
        assertEquals(
            "Album (Remastered)",
            MetadataCleaner.cleanAlbum("Album (We Live Here — Remastered Deluxe Edition)")
        )
    }
}
