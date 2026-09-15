package com.autolyrics.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitTitleVersionContextTest {

    @Test
    fun incidentalTitleWordDoesNotCreateVersionConflict() {
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Live Forever",
                candidateTitle = "Live Forever (Remastered)",
                requestedAlbum = "Definitely Maybe (Remastered)",
                candidateAlbum = "Definitely Maybe (Remastered)"
            )
        )
    }

    @Test
    fun explicitTitleVersionsStillRemainAuthoritative() {
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Live Forever (Live)",
                candidateTitle = "Live Forever (Remastered)",
                requestedAlbum = "Definitely Maybe (Live Remastered Edition)",
                candidateAlbum = "Definitely Maybe (Live Remastered Edition)"
            )
        )
    }

    @Test
    fun wholeTitleVersionWordIsNotAssumedToBeAQualifier() {
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Live",
                candidateTitle = "Live (Remastered)",
                requestedAlbum = "Album (Remastered)",
                candidateAlbum = "Album (Remastered)"
            )
        )
    }
}
