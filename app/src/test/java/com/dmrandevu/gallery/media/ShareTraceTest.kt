package com.dmrandevu.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTraceTest {

    @Test
    fun `a full-length video passes clean`() {
        assertEquals(emptyList<String>(), ShareTrace.suspicion(63_600, expectedMs = 63_620))
    }

    @Test
    fun `a video at Instagram's clip length is flagged even with nothing to compare`() {
        assertEquals(listOf("~15s"), ShareTrace.suspicion(15_190, expectedMs = null))
    }

    @Test
    fun `a video that lost length since the last step is flagged`() {
        assertEquals(listOf("~15s", "shorter"), ShareTrace.suspicion(15_000, expectedMs = 63_000))
        assertEquals(listOf("shorter"), ShareTrace.suspicion(30_000, expectedMs = 63_000))
    }

    @Test
    fun `container rounding between steps is not a loss`() {
        assertEquals(emptyList<String>(), ShareTrace.suspicion(33_100, expectedMs = 33_408))
    }

    @Test
    fun `a file whose length cannot be read is flagged`() {
        assertEquals(listOf("unreadable"), ShareTrace.suspicion(null, expectedMs = 20_000))
    }
}
