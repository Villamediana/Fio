package com.villamediana.fio.ui

import com.villamediana.fio.ui.components.Mosaic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicTest {
    @Test
    fun photosAllOfOneShapeStillGetDifferentWidths() {
        // Ten portrait photos from the same camera.
        val shapes = List(10) { Mosaic.shape(it, 0.75f) }
        assertTrue(shapes.toString(), shapes.distinct().size >= 4)
        val rows = Mosaic.rows(shapes, width = 379f, gap = 8f, target = 220f)
        // Every photo once, in order.
        assertEquals((0 until 10).toList(), rows.flatMap { it.items })
        // A full row fills the width exactly, no taller than the target.
        rows.filter { it.full }.forEach { r ->
            val w = r.items.sumOf { (shapes[it] * r.height).toDouble() } + 8.0 * (r.items.size - 1)
            assertEquals(379.0, w, 0.5)
            assertTrue(r.height <= 220f)
        }
        // Inside a row, the widths differ.
        val first = rows.first()
        assertTrue(first.items.map { shapes[it] }.distinct().size > 1)
    }

    @Test
    fun theLastRowDoesNotGrowTall() {
        val rows = Mosaic.rows(listOf(1f), width = 379f, gap = 8f, target = 220f)
        assertEquals(1, rows.size)
        assertFalse(rows.single().full)
        assertEquals(220f, rows.single().height)
    }

    @Test
    fun aDiaryCardStripTakesWhatFits() {
        val shapes = listOf(1.6f, 1.6f, 1.6f, 1.6f)
        val (n, h) = Mosaic.strip(shapes, width = 260f, gap = 6f, min = 52f, cap = 96f)
        assertEquals(2, n) // four wide ones would be too short
        assertTrue(h in 52f..96f)
        val (one, tall) = Mosaic.strip(listOf(0.75f), width = 260f, gap = 6f, min = 52f, cap = 96f)
        assertEquals(1, one)
        assertEquals(96f, tall)
    }
}
