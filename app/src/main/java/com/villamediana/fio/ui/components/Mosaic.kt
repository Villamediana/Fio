package com.villamediana.fio.ui.components

/**
 * Photos as a mosaic of different widths. Photos from one phone camera all have the same shape,
 * so a grid of them is a grid of equal boxes: each photo's own shape is varied by its place
 * ([RHYTHM]; the photo is cropped to it) and rows are filled edge to edge, so widths change from
 * one photo to the next and from one row to the next.
 */
object Mosaic {
    private val RHYTHM = floatArrayOf(1f, 1.45f, 0.78f, 1.22f, 0.9f, 1.6f, 0.82f, 1.12f)
    const val MIN = 0.56f
    const val MAX = 1.8f
    private const val LAST_ROW_STRETCH = 1.35f

    /** The shape (width over height) the photo at [index] takes on screen, from its own [ratio]. */
    fun shape(index: Int, ratio: Float): Float = (ratio * RHYTHM[index % RHYTHM.size]).coerceIn(MIN, MAX)

    /** A row of the mosaic: which photos, how tall, and whether it fills the width (the last may not). */
    data class Row(val items: List<Int>, val height: Float, val full: Boolean)

    /**
     * Rows across [width] ([gap] between photos): photos join a row until it fits at [target] tall
     * or less when stretched edge to edge. The last row, if it would grow much taller, stays at [target].
     */
    fun rows(shapes: List<Float>, width: Float, gap: Float, target: Float): List<Row> {
        val out = ArrayList<Row>()
        var current = ArrayList<Int>()
        var sum = 0f
        for (i in shapes.indices) {
            current += i
            sum += shapes[i]
            val h = (width - gap * (current.size - 1)) / sum
            if (h <= target) {
                out += Row(current, h, full = true)
                current = ArrayList()
                sum = 0f
            }
        }
        if (current.isNotEmpty()) {
            // The last row fills the width too when that doesn't make it much taller than the others.
            val h = (width - gap * (current.size - 1)) / sum
            out += if (h <= target * LAST_ROW_STRETCH) Row(current, h, full = true) else Row(current, target, full = false)
        }
        return out
    }

    /**
     * One row of at most [max] photos across [width]: as many as fit no shorter than [min] tall,
     * at most [cap] tall. Returns how many and how tall.
     */
    fun strip(shapes: List<Float>, width: Float, gap: Float, min: Float, cap: Float, max: Int = 4): Pair<Int, Float> {
        var n = minOf(max, shapes.size)
        while (n > 1) {
            val h = (width - gap * (n - 1)) / shapes.take(n).sum()
            if (h >= min) return n to minOf(h, cap)
            n--
        }
        return n to minOf(width / shapes.first(), cap)
    }
}
