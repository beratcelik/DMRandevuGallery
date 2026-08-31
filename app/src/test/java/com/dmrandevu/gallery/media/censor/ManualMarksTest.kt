package com.dmrandevu.gallery.media.censor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ManualMarksTest {

    private lateinit var marks: ManualMarks

    @Before
    fun setUp() {
        marks = ManualMarks(FakePrefs())
    }

    private fun window(fromMs: Long, toMs: Long) =
        CensorWindow(fromMs * 1_000, toMs * 1_000)

    @Test
    fun `remembers what was marked`() {
        marks.add("a", 0, window(1_000, 2_000))
        assertEquals(listOf(window(1_000, 2_000)), marks.forMedia("a", 0))
    }

    @Test
    fun `marks belong to one video, not to the conversation`() {
        marks.add("a", 0, window(1_000, 2_000))
        assertTrue(marks.forMedia("a", 1).isEmpty())
        assertTrue(marks.forMedia("b", 0).isEmpty())
    }

    /// Two presses over the same word are one beep, not a stutter.
    @Test
    fun `overlapping marks become one`() {
        marks.add("a", 0, window(1_000, 2_000))
        marks.add("a", 0, window(1_800, 2_600))
        assertEquals(listOf(window(1_000, 2_600)), marks.forMedia("a", 0))
    }

    @Test
    fun `marks far apart stay apart`() {
        marks.add("a", 0, window(1_000, 2_000))
        marks.add("a", 0, window(5_000, 6_000))
        assertEquals(2, marks.forMedia("a", 0).size)
    }

    @Test
    fun `they come back in order however they were made`() {
        marks.add("a", 0, window(5_000, 6_000))
        marks.add("a", 0, window(1_000, 2_000))
        assertEquals(listOf(window(1_000, 2_000), window(5_000, 6_000)), marks.forMedia("a", 0))
    }

    @Test
    fun `a mark can be taken off by touching it`() {
        marks.add("a", 0, window(1_000, 2_000))
        marks.add("a", 0, window(5_000, 6_000))
        marks.removeAt("a", 0, 1_500_000)
        assertEquals(listOf(window(5_000, 6_000)), marks.forMedia("a", 0))
    }

    @Test
    fun `touching empty space removes nothing`() {
        marks.add("a", 0, window(1_000, 2_000))
        marks.removeAt("a", 0, 9_000_000)
        assertEquals(1, marks.forMedia("a", 0).size)
    }

    /// A press and an instant release is a mis-tap, not a mark.
    @Test
    fun `a mark with no length is refused`() {
        marks.add("a", 0, CensorWindow(1_000_000, 1_000_000))
        marks.add("a", 0, CensorWindow(2_000_000, 1_000_000))
        assertTrue(marks.forMedia("a", 0).isEmpty())
    }

    @Test
    fun `clearing takes them all`() {
        marks.add("a", 0, window(1_000, 2_000))
        marks.add("a", 0, window(5_000, 6_000))
        marks.clear("a", 0)
        assertTrue(marks.forMedia("a", 0).isEmpty())
    }

    @Test
    fun `nothing marked reads back as nothing`() {
        assertTrue(marks.forMedia("never-touched", 3).isEmpty())
    }
}
