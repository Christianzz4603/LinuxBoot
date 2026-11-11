package linuxboot.desktop

import kotlin.math.max
import kotlin.math.min

/**
 * Recognises the I-beam (text) pointer from the cursor mask a VNC server sends. VNC has no
 * notion of keyboard focus, but toolkits show an I-beam over editable and selectable text,
 * which is the best signal available to a client. Heuristic: tall and thin, wide serifs only
 * at the two ends, a thin unbroken stem between them. It is tuned for common themes and
 * checked against synthetic shapes, not every cursor theme.
 */
object CursorShape {
    /** [mask] is row-major, w*h, true where the cursor is visible. */
    fun isTextCursor(mask: BooleanArray, w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0 || mask.size < w * h) return false
        val extent = IntArray(h)
        var top = -1
        var bottom = -1
        var minX = w
        var maxX = -1
        for (y in 0 until h) {
            var lo = w
            var hi = -1
            for (x in 0 until w) {
                if (mask[y * w + x]) {
                    if (x < lo) lo = x
                    if (x > hi) hi = x
                }
            }
            if (hi >= 0) {
                extent[y] = hi - lo + 1
                if (top < 0) top = y
                bottom = y
                minX = min(minX, lo)
                maxX = max(maxX, hi)
            }
        }
        if (top < 0) return false
        val bh = bottom - top + 1
        val bw = maxX - minX + 1
        if (bh < 12 || bh * 10 < bw * 15) return false // must be clearly taller than wide

        val wide = max(4, (bw * 6 + 9) / 10) // 60% of the width counts as a serif
        val topSerif = (0..2).any { extent[top + it] >= wide }
        val bottomSerif = (bh - 3 until bh).any { extent[top + it] >= wide }
        if (!topSerif || !bottomSerif) return false

        for (r in 3 until bh - 3) {
            val e = extent[top + r]
            if (e == 0 || e >= wide || e > 4) return false // the middle must be a thin, unbroken stem
        }
        return true
    }
}
