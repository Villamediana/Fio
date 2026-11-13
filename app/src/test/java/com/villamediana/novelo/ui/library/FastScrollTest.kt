package com.villamediana.novelo.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastScrollTest {
    /**
     * A diary of 200 days (a short day label, a card of its own height) scrolled pixel by pixel from
     * the top to the end: the handle's place starts at 0, ends at 1, only grows and never jumps.
     */
    @Test fun theHandleGlidesWithTheList() {
        val sizes = List(400) { if (it % 2 == 0) 50 else 150 + (it * 37 % 220) }
        val starts = sizes.runningFold(0) { a, s -> a + s }
        val viewport = 2_000
        val end = starts.last() - viewport
        var before = -1f
        var biggest = 0f
        for (s in 0..end) {
            val first = starts.indexOfLast { it <= s }.coerceAtMost(sizes.lastIndex)
            val bottom = s + viewport
            val last = starts.indexOfLast { it < bottom }.coerceAtMost(sizes.lastIndex)
            val firstOut = (s - starts[first]).toFloat() / sizes[first]
            val lastOut = ((starts[last] + sizes[last] - bottom).toFloat() / sizes[last]).coerceAtLeast(0f)
            val p = scrolled(sizes.size, first, firstOut, last, lastOut)
            if (s == 0) assertEquals(0f, p, 0f)
            if (before >= 0f) {
                assertTrue("went back at $s: $before → $p", p >= before - 1e-6f)
                biggest = maxOf(biggest, p - before)
            }
            before = p
        }
        assertEquals(1f, before, 1e-6f)
        assertTrue("a jump of $biggest", biggest < 0.001f)
    }

    /** A finger trembling on the line between two items doesn't move the list; well past it, it does. */
    @Test fun aStillFingerDoesNotShakeTheList() {
        assertEquals(10, nextItem(10.2f, -1))
        assertNull(nextItem(10.6f, 10))
        assertNull(nextItem(10.45f, 10))
        assertNull(nextItem(9.4f, 10))
        assertEquals(11, nextItem(10.8f, 10))
        assertEquals(9, nextItem(9.1f, 10))
        assertEquals(40, nextItem(40f, 12))
    }
}
