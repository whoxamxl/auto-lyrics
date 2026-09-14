package com.autolyrics.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LyricsDemandControllerTest {

    private val changes = mutableListOf<Boolean>()
    private val listener: (Boolean) -> Unit = changes::add

    @Before
    fun setUp() {
        LyricsDemandController.resetForTest()
        changes.clear()
        LyricsDemandController.addListener(listener)
    }

    @After
    fun tearDown() {
        LyricsDemandController.resetForTest()
    }

    @Test
    fun phoneForegroundActivatesAndLastPhoneStopDeactivates() {
        LyricsDemandController.onPhoneActivityStarted()
        LyricsDemandController.onPhoneActivityStarted()
        LyricsDemandController.onPhoneActivityStopped()

        assertTrue(LyricsDemandController.isActive)
        assertEquals(listOf(true), changes)

        LyricsDemandController.onPhoneActivityStopped()

        assertFalse(LyricsDemandController.isActive)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun carProjectionKeepsDemandActiveAfterPhoneCloses() {
        LyricsDemandController.onPhoneActivityStarted()
        LyricsDemandController.setCarProjectionConnected(true)
        LyricsDemandController.onPhoneActivityStopped()

        assertTrue(LyricsDemandController.isActive)
        assertEquals(listOf(true), changes)

        LyricsDemandController.setCarProjectionConnected(false)

        assertFalse(LyricsDemandController.isActive)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun carProjectionAloneActivatesDemand() {
        LyricsDemandController.setCarProjectionConnected(true)

        assertTrue(LyricsDemandController.isActive)
        assertEquals(listOf(true), changes)
    }

    @Test
    fun duplicateAndUnbalancedEventsDoNotEmitExtraChanges() {
        LyricsDemandController.onPhoneActivityStopped()
        LyricsDemandController.setCarProjectionConnected(false)
        LyricsDemandController.setCarProjectionConnected(true)
        LyricsDemandController.setCarProjectionConnected(true)
        LyricsDemandController.onPhoneActivityStopped()

        assertTrue(LyricsDemandController.isActive)
        assertEquals(listOf(true), changes)
    }

    @Test
    fun removingListenerStopsNotifications() {
        LyricsDemandController.removeListener(listener)
        LyricsDemandController.setCarProjectionConnected(true)

        assertTrue(LyricsDemandController.isActive)
        assertTrue(changes.isEmpty())
    }
}
