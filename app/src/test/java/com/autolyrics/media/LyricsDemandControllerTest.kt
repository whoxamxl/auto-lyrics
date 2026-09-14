package com.autolyrics.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsDemandControllerTest {

    @Test
    fun phoneForegroundActivatesAndLastPhoneStopDeactivates() {
        val changes = mutableListOf<Boolean>()
        val controller = LyricsDemandController(changes::add)

        controller.onPhoneActivityStarted()
        controller.onPhoneActivityStarted()
        controller.onPhoneActivityStopped()

        assertTrue(controller.isActive)
        assertEquals(listOf(true), changes)

        controller.onPhoneActivityStopped()

        assertFalse(controller.isActive)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun carProjectionKeepsDemandActiveAfterPhoneCloses() {
        val changes = mutableListOf<Boolean>()
        val controller = LyricsDemandController(changes::add)

        controller.onPhoneActivityStarted()
        controller.setCarProjectionConnected(true)
        controller.onPhoneActivityStopped()

        assertTrue(controller.isActive)
        assertEquals(listOf(true), changes)

        controller.setCarProjectionConnected(false)

        assertFalse(controller.isActive)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun carProjectionAloneActivatesDemand() {
        val changes = mutableListOf<Boolean>()
        val controller = LyricsDemandController(changes::add)

        controller.setCarProjectionConnected(true)

        assertTrue(controller.isActive)
        assertEquals(listOf(true), changes)
    }

    @Test
    fun duplicateAndUnbalancedEventsDoNotEmitExtraChanges() {
        val changes = mutableListOf<Boolean>()
        val controller = LyricsDemandController(changes::add)

        controller.onPhoneActivityStopped()
        controller.setCarProjectionConnected(false)
        controller.setCarProjectionConnected(true)
        controller.setCarProjectionConnected(true)
        controller.onPhoneActivityStopped()

        assertTrue(controller.isActive)
        assertEquals(listOf(true), changes)
    }
}
