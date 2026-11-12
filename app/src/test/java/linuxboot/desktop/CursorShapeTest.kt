package linuxboot.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorShapeTest {
    private fun mask(vararg rows: String): Triple<BooleanArray, Int, Int> {
        val h = rows.size
        val w = rows.maxOf { it.length }
        val m = BooleanArray(w * h)
        rows.forEachIndexed { y, r -> r.forEachIndexed { x, c -> if (c == '#') m[y * w + x] = true } }
        return Triple(m, w, h)
    }

    private fun isText(vararg rows: String): Boolean {
        val (m, w, h) = mask(*rows)
        return CursorShape.isTextCursor(m, w, h)
    }

    private val stem = "....#...."

    @Test
    fun ibeamIsRecognised() {
        val rows = ArrayList<String>()
        rows.add(".#######.")
        repeat(14) { rows.add(stem) }
        rows.add(".#######.")
        assertTrue(isText(*rows.toTypedArray()))
    }

    @Test
    fun ibeamWithThickSerifsIsRecognised() {
        val rows = ArrayList<String>()
        rows.add(".#######.")
        rows.add("..#####..")
        repeat(16) { rows.add(stem) }
        rows.add("..#####..")
        rows.add(".#######.")
        assertTrue(isText(*rows.toTypedArray()))
    }

    @Test
    fun outlinedIbeamWithThickStemIsRecognised() {
        // Many themes draw a dark core plus a light outline, so the stem is 5 px wide.
        val rows = ArrayList<String>()
        repeat(2) { rows.add("###########") }
        repeat(16) { rows.add("...#####...") }
        repeat(2) { rows.add("###########") }
        assertTrue(isText(*rows.toTypedArray()))
    }

    @Test
    fun verticalDoubleArrowIsNotText() {
        val rows = ArrayList<String>()
        rows.addAll(listOf("....#....", "...###...", "..#####..", ".#######.", "#########"))
        repeat(11) { rows.add(stem) }
        rows.addAll(listOf("#########", ".#######.", "..#####..", "...###...", "....#...."))
        assertFalse(isText(*rows.toTypedArray()))
    }

    @Test
    fun ordinaryArrowPointerIsNotText() {
        assertFalse(
            isText(
                "#.........", "##........", "###.......", "####......", "#####.....", "######....",
                "#######...", "########..", "#########.", "##########", "#####.....", "##.##.....",
                "#..##.....", "...##.....", "....##....", "....##....",
            ),
        )
    }

    @Test
    fun hourglassIsNotText() {
        val w = 11
        val h = 20
        val m = BooleanArray(w * h)
        for (y in 0 until h) {
            val half = Math.round(5.0 * Math.abs(y - 9.5) / 9.5).toInt()
            for (x in (5 - half)..(5 + half)) m[y * w + x] = true
        }
        assertFalse(CursorShape.isTextCursor(m, w, h))
    }

    @Test
    fun crosshairAndPlainLineAreNotText() {
        assertFalse(CursorShape.isTextCursor(BooleanArray(15 * 15) { it / 15 == 7 || it % 15 == 7 }, 15, 15))
        val rows = Array(16) { stem } // a bare vertical line has no serifs
        assertFalse(isText(*rows))
    }

    @Test
    fun emptyAndTinyShapesAreNotText() {
        assertFalse(CursorShape.isTextCursor(BooleanArray(0), 0, 0))
        assertFalse(CursorShape.isTextCursor(BooleanArray(5 * 8) { true }, 5, 8))
    }

    @Test
    fun hashDistinguishesShapesAndIsStable() {
        val (a, aw, ah) = mask("#.", ".#")
        val (b, bw, bh) = mask(".#", "#.")
        assertEquals(CursorShape.hash(a, aw, ah), CursorShape.hash(a.copyOf(), aw, ah))
        assertNotEquals(CursorShape.hash(a, aw, ah), CursorShape.hash(b, bw, bh))
    }

    @Test
    fun artDrawsTheMask() {
        val (m, w, h) = mask("#.", ".#")
        assertEquals("#.\n.#", CursorShape.art(m, w, h))
    }
}
