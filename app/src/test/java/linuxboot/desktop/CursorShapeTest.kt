package linuxboot.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorShapeTest {
    private fun isText(vararg rows: String): Boolean {
        val h = rows.size
        val w = rows.maxOf { it.length }
        val m = BooleanArray(w * h)
        rows.forEachIndexed { y, r -> r.forEachIndexed { x, c -> if (c == '#') m[y * w + x] = true } }
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
    fun crosshairAndPlainLineAreNotText() {
        assertFalse(CursorShape.isTextCursor(BooleanArray(15 * 15) { it / 15 == 7 || it % 15 == 7 }, 15, 15))
        // a bare vertical line has no serifs
        val rows = Array(16) { stem }
        assertFalse(isText(*rows))
    }

    @Test
    fun emptyAndTinyShapesAreNotText() {
        assertFalse(CursorShape.isTextCursor(BooleanArray(0), 0, 0))
        assertFalse(CursorShape.isTextCursor(BooleanArray(5 * 8) { true }, 5, 8))
    }
}
