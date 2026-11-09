package linuxboot.desktop

import android.view.KeyEvent

/** X11 keysyms used by the viewer, and the Android key code to keysym mapping. */
object Keysyms {
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val INSERT = 0xFF63
    const val DELETE = 0xFFFF
    const val F1 = 0xFFBE
    const val SHIFT = 0xFFE1
    const val CTRL = 0xFFE3
    const val ALT = 0xFFE9
    const val SUPER = 0xFFEB

    fun fromChar(codePoint: Int): Int = if (codePoint < 0x100) codePoint else 0x01000000 + codePoint

    /** Null when the key has no mapping (it is then left to Android). */
    fun fromAndroid(ev: KeyEvent): Int? {
        val code = ev.keyCode
        if (code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return F1 + (code - KeyEvent.KEYCODE_F1)
        when (code) {
            KeyEvent.KEYCODE_DEL -> return BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> return DELETE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return RETURN
            KeyEvent.KEYCODE_TAB -> return TAB
            KeyEvent.KEYCODE_ESCAPE -> return ESCAPE
            KeyEvent.KEYCODE_INSERT -> return INSERT
            KeyEvent.KEYCODE_MOVE_HOME -> return HOME
            KeyEvent.KEYCODE_MOVE_END -> return END
            KeyEvent.KEYCODE_PAGE_UP -> return PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> return PAGE_DOWN
            KeyEvent.KEYCODE_DPAD_UP -> return UP
            KeyEvent.KEYCODE_DPAD_DOWN -> return DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> return LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> return RIGHT
            KeyEvent.KEYCODE_SHIFT_LEFT -> return SHIFT
            KeyEvent.KEYCODE_SHIFT_RIGHT -> return 0xFFE2
            KeyEvent.KEYCODE_CTRL_LEFT -> return CTRL
            KeyEvent.KEYCODE_CTRL_RIGHT -> return 0xFFE4
            KeyEvent.KEYCODE_ALT_LEFT -> return ALT
            KeyEvent.KEYCODE_ALT_RIGHT -> return 0xFFEA
            KeyEvent.KEYCODE_META_LEFT -> return SUPER
            KeyEvent.KEYCODE_META_RIGHT -> return 0xFFEC
            KeyEvent.KEYCODE_CAPS_LOCK -> return 0xFFE5
            KeyEvent.KEYCODE_SPACE -> return 0x20
        }
        // Printable: use the character with only Shift applied, so Ctrl/Alt combos arrive
        // as the plain key plus the separately forwarded modifier keys.
        val ch = ev.getUnicodeChar(ev.metaState and KeyEvent.META_SHIFT_MASK)
        if (ch == 0) return null
        return fromChar(ch)
    }
}
