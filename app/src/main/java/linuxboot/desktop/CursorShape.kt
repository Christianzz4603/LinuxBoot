package linuxboot.desktop

import kotlin.math.max
import kotlin.math.min

/**
 * Recognises the I-beam (text) pointer from the cursor mask a VNC server sends. VNC has no
 * notion of keyboard focus, but toolkits show an I-beam over editable and selectable text,
 * which is the best signal available to a client.
 *
 * Heuristic: clearly taller than wide; a wide serif in the first two and last two rows (a
 * pointer arrow or resize arrow has a narrow tip there); and a stem through the middle 40% of
 * rows that is unbroken, of nearly constant width, and clearly narrower than the serifs. The
 * stem and serifs may be several pixels thick, because many themes outline the I-beam.
 * Anything it misses can be taught with [hash] (see ViewerController.learnCurrentPointer).
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
        if (bh < 10 || bh * 10 < bw * 13) return false // clearly taller than wide

        val serifMin = max(4, (bw * 6 + 9) / 10) // 60% of the width, at least 4 px
        val topSerif = max(extent[top], extent[top + 1])
        val bottomSerif = max(extent[bottom], extent[bottom - 1])
        if (topSerif < serifMin || bottomSerif < serifMin) return false

        val from = top + bh * 3 / 10
        val to = top + bh * 7 / 10
        var midMax = 0
        var midMin = Int.MAX_VALUE
        for (y in from..to) {
            val e = extent[y]
            if (e == 0) return false // the stem must be unbroken
            midMax = max(midMax, e)
            midMin = min(midMin, e)
        }
        if (midMax - midMin > max(2, bw / 4)) return false // roughly constant width (rules out hourglasses)
        return midMax * 10 <= min(topSerif, bottomSerif) * 8 // clearly narrower than the serifs
    }

    /** Stable identity of a pointer shape, used to remember shapes the user taught us. */
    fun hash(mask: BooleanArray, w: Int, h: Int): Int {
        var x = 17 * 31 + w
        x = x * 31 + h
        for (b in mask) x = x * 31 + if (b) 1 else 0
        return x
    }

    /** ASCII picture of the mask (capped at 40x40), for showing what the server sent. */
    fun art(mask: BooleanArray, w: Int, h: Int): String {
        if (w <= 0 || h <= 0 || mask.size < w * h) return ""
        val sb = StringBuilder()
        for (y in 0 until min(h, 40)) {
            for (x in 0 until min(w, 40)) sb.append(if (mask[y * w + x]) '#' else '.')
            if (y < min(h, 40) - 1) sb.append('\n')
        }
        return sb.toString()
    }
}
