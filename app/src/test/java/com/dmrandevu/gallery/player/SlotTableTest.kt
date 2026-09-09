package com.dmrandevu.gallery.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotTableTest {

    private val table = SlotTable(size = 2)

    @Test
    fun `a key keeps the slot it was given`() {
        val first = table.claim("a")
        assertEquals(first, table.claim("a"))
        assertEquals(first, table.holding("a"))
    }

    @Test
    fun `a second key takes the other slot`() {
        assertNotEquals(table.claim("a"), table.claim("b"))
    }

    @Test
    fun `a third key evicts the least recently used`() {
        val a = table.claim("a")
        table.claim("b")
        table.claim("a") // a is now the more recent of the two
        assertNotEquals("c took the slot that was just used", a, table.claim("c"))
        assertEquals(-1, table.holding("b"))
    }

    @Test
    fun `claiming forgets what the slot was loaded with`() {
        val a = table.claim("a")
        table.setUrl(a, "one.mp4")
        table.claim("b")
        table.claim("c") // evicts a
        assertEquals(null, table.urlAt(a))
    }

    @Test
    fun `re-claiming your own slot keeps what is loaded on it`() {
        val a = table.claim("a")
        table.setUrl(a, "one.mp4")
        assertEquals(a, table.claim("a"))
        assertEquals("one.mp4", table.urlAt(a))
    }

    /**
     * The bug this class exists for.
     *
     * Asking which slot a key would get must not give it one. The pre-buffer asks so it can
     * refuse slots committed to the GL pipeline; when asking was done by claiming, the refusal
     * left the slot assigned to a conversation whose video was never loaded and evicted the one
     * on screen — which then bound its view to a player with nothing prepared and showed black,
     * with no error anywhere to say why.
     */
    @Test
    fun `asking which slot a key would get does not give it one`() {
        val a = table.claim("a")
        table.claim("b")

        val wouldBe = table.wouldServe("c")
        assertEquals("a lost its slot to a question", a, table.holding("a"))
        assertTrue("b lost its slot to a question", table.holding("b") >= 0)
        assertEquals(-1, table.holding("c"))
        // And it answers the same thing the claim would have done.
        assertEquals(wouldBe, table.claim("c"))
    }

    @Test
    fun `asking does not disturb what is loaded either`() {
        val a = table.claim("a")
        table.setUrl(a, "one.mp4")
        table.claim("b")
        table.wouldServe("c")
        assertEquals("one.mp4", table.urlAt(a))
    }

    @Test
    fun `the gl mark sticks to the slot`() {
        val a = table.claim("a")
        assertFalse(table.usesGlAt(a))
        table.markUsesGl(a)
        assertTrue(table.usesGlAt(a))
        // Still true once the slot has been handed to somebody else: the player is committed for
        // good, whoever is using it.
        table.claim("b")
        table.claim("c")
        assertTrue(table.usesGlAt(a))
    }

    @Test
    fun `touching keeps a slot from being the next evicted`() {
        val a = table.claim("a")
        val b = table.claim("b")
        table.touch(a)
        assertEquals("the touched slot was evicted", b, table.claim("c"))
    }
}
