package linuxboot.desktop

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ModState { OFF, ONCE, LOCKED }

/**
 * Holds what the viewer shows and does: the framebuffer bitmap, zoom and pan, a trackpad
 * style cursor, mouse buttons and latched modifier keys. Input is forwarded to [client].
 */
class ViewerController(
    val client: RfbClient,
    /** Pointer shapes the user taught us are the text pointer (hashes from [CursorShape.hash]). */
    val learned: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
    private val onLearn: (Set<Int>) -> Unit = {},
) : RfbClient.Listener {
    var frame by mutableIntStateOf(0)
        private set
    var fbW by mutableIntStateOf(client.width)
        private set
    var fbH by mutableIntStateOf(client.height)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var panX by mutableFloatStateOf(0f)
        private set
    var panY by mutableFloatStateOf(0f)
        private set
    var cursorX by mutableFloatStateOf(0f)
        private set
    var cursorY by mutableFloatStateOf(0f)
        private set
    var closed by mutableStateOf(false)
        private set
    var closedReason by mutableStateOf<String?>(null)
        private set

    /** True while the server shows an I-beam pointer (over editable or selectable text). */
    var textCursor by mutableStateOf(false)
        private set

    /** Open the on-screen keyboard when tapping while the pointer is an I-beam. */
    var autoKeyboard by mutableStateOf(true)

    /** Bilinear smoothing when the picture is enlarged. Off by default: crisp pixels beat a blurry picture. */
    var smoothScaling by mutableStateOf(false)

    /** ASCII picture of the last pointer shape the server sent, to diagnose text detection. */
    var cursorArt by mutableStateOf("")
        private set

    /** How many pointer shapes the server has sent (0 means it never sends them). */
    var cursorSeen by mutableIntStateOf(0)
        private set

    private var lastShapeHash = 0

    /** The user says the pointer currently shown is the text pointer: remember it. */
    fun learnCurrentPointer() {
        if (cursorSeen == 0) return
        learned.add(lastShapeHash)
        onLearn(learned)
        textCursor = true
    }

    fun forgetLearnedPointers() {
        learned.clear()
        onLearn(learned)
    }

    /** false = trackpad (cursor offset from the finger), true = the cursor jumps to the touch. */
    var directTouch by mutableStateOf(false)

    /** When true, a one-finger drag pans the view instead of moving the cursor. */
    var panMode by mutableStateOf(false)

    val mods = mutableStateMapOf<Int, ModState>()

    var lastTapUp = 0L
    var lastTapCount = 0

    private var buttons = 0
    private var viewW = 1f
    private var viewH = 1f
    private val bmpLock = Any()
    private var bitmap: Bitmap? = null

    // ---- geometry -------------------------------------------------------------------------

    /**
     * Fit-to-screen scale. When it is within 6% of a whole number it snaps to that number: integer
     * scaling keeps every desktop pixel the same size, which is what makes text and icons crisp.
     * (The few pixels this can push past the edge are reachable by panning.)
     */
    private val fit: Float
        get() {
            if (fbW == 0 || fbH == 0) return 1f
            val raw = min(viewW / fbW, viewH / fbH)
            val n = Math.round(raw)
            return if (n >= 1 && abs(raw - n) / n < 0.06f) n.toFloat() else raw
        }

    val scale: Float get() = fit * zoom
    val baseX: Float get() = (viewW - fbW * scale) / 2f + panX
    val baseY: Float get() = (viewH - fbH * scale) / 2f + panY

    fun setViewSize(w: Float, h: Float) {
        viewW = max(1f, w)
        viewH = max(1f, h)
        clampPan()
    }

    private fun clampPan() {
        val s = scale
        val mx = max(0f, (fbW * s - viewW) / 2f)
        val my = max(0f, (fbH * s - viewH) / 2f)
        panX = panX.coerceIn(-mx, mx)
        panY = panY.coerceIn(-my, my)
    }

    fun panBy(d: Offset) {
        panX += d.x
        panY += d.y
        clampPan()
    }

    fun zoomBy(factor: Float, focal: Offset) {
        val old = scale
        val fx = (focal.x - baseX) / old
        val fy = (focal.y - baseY) / old
        zoom = (zoom * factor).coerceIn(1f, 8f)
        val ns = scale
        panX = focal.x - fx * ns - (viewW - fbW * ns) / 2f
        panY = focal.y - fy * ns - (viewH - fbH * ns) / 2f
        clampPan()
    }

    fun fitToScreen() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    /** One framebuffer pixel per screen pixel (or fit, if the framebuffer is smaller). */
    fun actualSize() {
        zoom = max(1f, 1f / fit).coerceAtMost(8f)
        panX = 0f
        panY = 0f
        clampPan()
    }

    private fun keepCursorVisible() {
        val s = scale
        val sx = baseX + cursorX * s
        val sy = baseY + cursorY * s
        val m = 24f
        if (sx < m) panX += m - sx else if (sx > viewW - m) panX -= sx - (viewW - m)
        if (sy < m) panY += m - sy else if (sy > viewH - m) panY -= sy - (viewH - m)
        clampPan()
    }

    // ---- pointer --------------------------------------------------------------------------

    private fun sendPointer() = client.pointer(buttons, cursorX.toInt(), cursorY.toInt())

    private fun setCursorFromScreen(p: Offset) {
        val s = scale
        cursorX = ((p.x - baseX) / s).coerceIn(0f, max(0f, fbW - 1f))
        cursorY = ((p.y - baseY) / s).coerceIn(0f, max(0f, fbH - 1f))
    }

    fun moveBy(d: Offset) {
        val s = scale
        cursorX = (cursorX + d.x / s).coerceIn(0f, max(0f, fbW - 1f))
        cursorY = (cursorY + d.y / s).coerceIn(0f, max(0f, fbH - 1f))
        keepCursorVisible()
        sendPointer()
    }

    fun moveTo(p: Offset) {
        setCursorFromScreen(p)
        sendPointer()
    }

    /** A physical mouse reports absolute positions and its own button state. */
    fun hardwareMouse(p: Offset, mask: Int) {
        setCursorFromScreen(p)
        buttons = mask
        sendPointer()
    }

    fun press(mask: Int) {
        buttons = buttons or mask
        sendPointer()
    }

    fun release(mask: Int) {
        buttons = buttons and mask.inv()
        sendPointer()
    }

    fun click(mask: Int) {
        press(mask)
        release(mask)
    }

    /** steps > 0 scrolls up, steps < 0 scrolls down. */
    fun scroll(steps: Int) {
        val x = cursorX.toInt()
        val y = cursorY.toInt()
        val bit = if (steps > 0) 8 else 16
        repeat(abs(steps)) {
            client.pointer(buttons or bit, x, y)
            client.pointer(buttons, x, y)
        }
    }

    // ---- keyboard -------------------------------------------------------------------------

    private fun releaseOneShot() {
        mods.filter { it.value == ModState.ONCE }.keys.toList().forEach {
            client.key(false, it)
            mods[it] = ModState.OFF
        }
    }

    /** Taps a key; latched modifiers are held around it, one-shot ones are then released. */
    fun tap(keysym: Int) {
        client.key(true, keysym)
        client.key(false, keysym)
        releaseOneShot()
    }

    /** OFF -> ONCE (next key only) -> LOCKED -> OFF. */
    fun cycleModifier(keysym: Int) {
        when (mods[keysym] ?: ModState.OFF) {
            ModState.OFF -> { client.key(true, keysym); mods[keysym] = ModState.ONCE }
            ModState.ONCE -> mods[keysym] = ModState.LOCKED
            ModState.LOCKED -> { client.key(false, keysym); mods[keysym] = ModState.OFF }
        }
    }

    fun hardwareKey(down: Boolean, keysym: Int) = client.key(down, keysym)

    fun typeText(text: String) {
        text.codePoints().forEach { tap(Keysyms.fromChar(it)) }
    }

    // ---- framebuffer ----------------------------------------------------------------------

    fun <T> withBitmap(block: (Bitmap?) -> T): T = synchronized(bmpLock) { block(bitmap) }

    override fun onSize(width: Int, height: Int) {
        synchronized(bmpLock) {
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            b.setPixels(client.pixels, 0, width, 0, 0, width, height)
            bitmap = b
        }
        fbW = width
        fbH = height
        cursorX = cursorX.coerceIn(0f, max(0f, width - 1f))
        cursorY = cursorY.coerceIn(0f, max(0f, height - 1f))
        clampPan()
        frame++
    }

    override fun onUpdate(x: Int, y: Int, w: Int, h: Int) {
        synchronized(bmpLock) {
            bitmap?.setPixels(client.pixels, y * client.width + x, client.width, x, y, w, h)
        }
        frame++
    }

    override fun onCursorShape(w: Int, h: Int, mask: BooleanArray) {
        lastShapeHash = CursorShape.hash(mask, w, h)
        cursorArt = CursorShape.art(mask, w, h)
        cursorSeen++
    }

    override fun onCursor(isText: Boolean) {
        textCursor = isText || lastShapeHash in learned
    }

    override fun onClosed(reason: String?) {
        closedReason = reason
        closed = true
    }
}
