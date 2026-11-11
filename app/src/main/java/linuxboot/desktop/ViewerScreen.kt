package linuxboot.desktop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import linuxboot.firmware.FirmwareViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

private const val LEFT = 1
private const val MIDDLE = 2
private const val RIGHT = 4

private enum class Panel { NONE, SETTINGS, RESOLUTION, INFO, EXIT }

private val Ink = Color(0xFF0000AA)

/** Touch gestures modelled on a trackpad (the interaction concept of common VNC viewers). */
private fun Modifier.touchGestures(ctl: ViewerController, onTouch: () -> Unit, onTapped: () -> Unit): Modifier =
    pointerInput(ctl) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type == PointerType.Mouse) {
                // Physical mouse is handled by its own handler; just wait for release.
                do {
                    val e = awaitPointerEvent()
                } while (e.changes.any { it.pressed })
                return@awaitEachGesture
            }
            onTouch()
            val t0 = down.uptimeMillis
            var tEnd = t0
            var maxPointers = 1
            var prevCount = 1
            var moved = 0f
            var lastCentroid = down.position
            var lastSpread = 0f
            var holding = false
            var scrollAcc = 0f

            if (ctl.directTouch) {
                ctl.moveTo(down.position)
                ctl.press(LEFT)
                holding = true
            } else if (t0 - ctl.lastTapUp < 300L && ctl.lastTapCount == 1) {
                // Second tap of a double tap: press now, so holding and moving drags.
                ctl.press(LEFT)
                holding = true
            }

            while (true) {
                val e = awaitPointerEvent()
                val pressed = e.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    tEnd = e.changes.maxOf { it.uptimeMillis }
                    break
                }
                val n = pressed.size
                maxPointers = maxOf(maxPointers, n)
                val centroid = Offset(
                    pressed.map { it.position.x }.average().toFloat(),
                    pressed.map { it.position.y }.average().toFloat(),
                )
                val spread = pressed.map { (it.position - centroid).getDistance() }.average().toFloat()
                if (n != prevCount) {
                    lastCentroid = centroid
                    lastSpread = spread
                    prevCount = n
                }
                val d = centroid - lastCentroid
                moved += d.getDistance() + abs(spread - lastSpread)

                if (n == 1 && maxPointers == 1) {
                    when {
                        ctl.directTouch -> ctl.moveTo(centroid)
                        ctl.panMode && !holding -> ctl.panBy(d)
                        else -> ctl.moveBy(d)
                    }
                } else if (n >= 2) {
                    val ratio = if (lastSpread > 0f) spread / lastSpread else 1f
                    if (abs(ratio - 1f) > 0.02f) {
                        ctl.zoomBy(ratio, centroid)
                    } else {
                        // Fingers moving up scroll the page down, like on a touchscreen.
                        scrollAcc += d.y
                        while (abs(scrollAcc) >= 40f) {
                            ctl.scroll(if (scrollAcc < 0) -1 else 1)
                            scrollAcc -= if (scrollAcc < 0) -40f else 40f
                        }
                    }
                }
                lastCentroid = centroid
                lastSpread = spread
                e.changes.forEach { it.consume() }
            }

            val dt = tEnd - t0
            when {
                ctl.directTouch -> {
                    ctl.release(LEFT)
                    if (moved < slop && dt < 300L) onTapped()
                }
                holding -> {
                    ctl.release(LEFT)
                    ctl.lastTapUp = 0L
                }
                maxPointers == 1 -> {
                    if (moved < slop && dt < 300L) {
                        ctl.click(LEFT)
                        onTapped()
                        ctl.lastTapUp = tEnd
                        ctl.lastTapCount = 1
                    } else {
                        ctl.lastTapUp = 0L
                    }
                }
                maxPointers == 2 -> if (moved < slop * 2 && dt < 400L) ctl.click(RIGHT)
                else -> if (moved < slop * 3 && dt < 400L) ctl.click(MIDDLE)
            }
        }
    }

/** A physical mouse: absolute pointer, real buttons and wheel. Touch is not affected. */
private fun Modifier.mouseInput(ctl: ViewerController): Modifier = pointerInput(ctl) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            val c = e.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
            var mask = 0
            if (e.buttons.isPrimaryPressed) mask = mask or LEFT
            if (e.buttons.isTertiaryPressed) mask = mask or MIDDLE
            if (e.buttons.isSecondaryPressed) mask = mask or RIGHT
            ctl.hardwareMouse(c.position, mask)
            if (e.type == PointerEventType.Scroll) {
                val dy = c.scrollDelta.y
                if (dy != 0f) ctl.scroll(if (dy < 0) 1 else -1)
            }
        }
    }
}

/** Hardware keyboards only; the on-screen keyboard's text arrives through the hidden field. */
private fun forwardKey(ctl: ViewerController, ev: KeyEvent): Boolean {
    val nk = ev.nativeKeyEvent
    if (nk.deviceId == -1) return false
    val sym = Keysyms.fromAndroid(nk) ?: return false
    return when (ev.type) {
        KeyEventType.KeyDown -> { ctl.hardwareKey(true, sym); true }
        KeyEventType.KeyUp -> { ctl.hardwareKey(false, sym); true }
        else -> false
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun inputSummary(): String {
    var kb = 0
    var mice = 0
    for (id in InputDevice.getDeviceIds()) {
        val d = InputDevice.getDevice(id) ?: continue
        if (d.isVirtual) continue
        if (d.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
            d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        ) kb++
        if (d.sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) mice++
    }
    return "PHYSICAL KEYBOARDS: $kb   MICE: $mice"
}

@Composable
private fun TBtn(label: String, active: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) Color.Black else Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Color(0xFFC0C0C0) else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun PanelBox(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xEE101060))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        content()
        TBtn("CLOSE", onClick = onClose)
    }
}

@Composable
private fun PLine(text: String) =
    Text(text, color = Color(0xFFE6E6E6), fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
private fun VirtualKeyboard(ctl: ViewerController, onSoft: () -> Unit, modifier: Modifier) {
    fun mod(label: String, sym: Int) = Pair(label, sym)
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xDD101040))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(mod("CTRL", Keysyms.CTRL), mod("ALT", Keysyms.ALT), mod("SHIFT", Keysyms.SHIFT), mod("SUPER", Keysyms.SUPER))
                .forEach { (label, sym) ->
                    val st = ctl.mods[sym] ?: ModState.OFF
                    TBtn(
                        label + when (st) { ModState.OFF -> ""; ModState.ONCE -> "*"; ModState.LOCKED -> "#" },
                        active = st != ModState.OFF,
                    ) { ctl.cycleModifier(sym) }
                }
            TBtn("ABC") { onSoft() }
            TBtn("ESC") { ctl.tap(Keysyms.ESCAPE) }
            TBtn("TAB") { ctl.tap(Keysyms.TAB) }
            TBtn("DEL") { ctl.tap(Keysyms.DELETE) }
            TBtn("INS") { ctl.tap(Keysyms.INSERT) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TBtn("HOME") { ctl.tap(Keysyms.HOME) }
            TBtn("END") { ctl.tap(Keysyms.END) }
            TBtn("PGUP") { ctl.tap(Keysyms.PAGE_UP) }
            TBtn("PGDN") { ctl.tap(Keysyms.PAGE_DOWN) }
            TBtn("<") { ctl.tap(Keysyms.LEFT) }
            TBtn("^") { ctl.tap(Keysyms.UP) }
            TBtn("v") { ctl.tap(Keysyms.DOWN) }
            TBtn(">") { ctl.tap(Keysyms.RIGHT) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (i in 1..12) TBtn("F$i") { ctl.tap(Keysyms.F1 + i - 1) }
        }
    }
}

@Composable
fun ViewerScreen(vm: FirmwareViewModel) {
    // The desktop is landscape: lock to landscape (either direction, following the sensor)
    // while it is open and restore whatever the app had when it closes. The activity handles
    // orientation changes itself, so the session is not restarted by the rotation.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = previous }
    }

    val ctl = vm.viewer
    if (ctl == null) {
        BackHandler { vm.exitViewer(false) }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("NO ACTIVE DESKTOP", color = Color.White, fontFamily = FontFamily.Monospace)
        }
        return
    }

    var toolbarVisible by remember { mutableStateOf(true) }
    var minimized by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    var tbOffset by remember { mutableStateOf(Offset.Zero) }
    var touchTick by remember { mutableIntStateOf(0) }
    var showKeyboard by remember { mutableStateOf(false) }
    var softKeyboard by remember { mutableStateOf(false) }
    var autoOpened by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    val rootFocus = remember { FocusRequester() }

    // VNC does not report keyboard focus, but the pointer turns into an I-beam over text.
    // Tapping on text opens the keyboard; tapping elsewhere closes it again if we opened it.
    fun onDesktopTap() {
        if (!ctl.autoKeyboard) return
        if (ctl.textCursor) {
            if (!softKeyboard) {
                softKeyboard = true
                autoOpened = true
            }
        } else if (softKeyboard && autoOpened) {
            softKeyboard = false
            autoOpened = false
        }
    }
    val softFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    LaunchedEffect(touchTick, pinned, toolbarVisible, panel, showKeyboard) {
        if (toolbarVisible && !pinned && panel == Panel.NONE && !showKeyboard) {
            delay(4000)
            toolbarVisible = false
        }
    }
    LaunchedEffect(softKeyboard) {
        if (softKeyboard) {
            softFocus.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
            rootFocus.requestFocus()
        }
    }
    BackHandler {
        // Back never closes the desktop by accident; it brings the toolbar back.
        toolbarVisible = true
        touchTick++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .displayCutoutPadding()
            .imePadding()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { forwardKey(ctl, it) },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { ctl.setViewSize(it.width.toFloat(), it.height.toFloat()) }
                .touchGestures(ctl, onTouch = { touchTick++ }, onTapped = { onDesktopTap() })
                .mouseInput(ctl),
        ) {
            ctl.frame // subscribe to framebuffer changes
            val s = ctl.scale
            val bx = ctl.baseX
            val by = ctl.baseY
            ctl.withBitmap { bmp ->
                if (bmp != null) {
                    translate(bx, by) {
                        scale(s, s, pivot = Offset.Zero) {
                            drawImage(bmp.asImageBitmap(), filterQuality = FilterQuality.Low)
                        }
                    }
                }
            }
            val cx = bx + ctl.cursorX * s
            val cy = by + ctl.cursorY * s
            val arrow = Path().apply {
                moveTo(cx, cy)
                lineTo(cx, cy + 22f)
                lineTo(cx + 6f, cy + 17f)
                lineTo(cx + 15f, cy + 17f)
                close()
            }
            drawPath(arrow, Color.White)
            drawPath(arrow, Color.Black, style = Stroke(2f))
        }

        if (softKeyboard) {
            var field by remember { mutableStateOf(TextFieldValue(" ", TextRange(1))) }
            BasicTextField(
                value = field,
                onValueChange = { v ->
                    when {
                        v.text.isEmpty() -> ctl.tap(Keysyms.BACKSPACE)
                        v.text.length > 1 -> ctl.typeText(v.text.substring(1))
                    }
                    field = TextFieldValue(" ", TextRange(1))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { ctl.tap(Keysyms.RETURN) }),
                modifier = Modifier.size(1.dp).alpha(0f).focusRequester(softFocus),
            )
        }

        if (showKeyboard) {
            VirtualKeyboard(
                ctl,
                onSoft = {
                    softKeyboard = !softKeyboard
                    autoOpened = false
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (!toolbarVisible) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(56.dp, 18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0x66FFFFFF))
                    .clickable { toolbarVisible = true; touchTick++ },
            )
        } else {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(tbOffset.x.roundToInt(), tbOffset.y.roundToInt()) }
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC101040))
                    .padding(4.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "::",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                tbOffset += drag
                                touchTick++
                            }
                        },
                )
                if (minimized) {
                    TBtn("+") { minimized = false; touchTick++ }
                } else {
                    TBtn("KEYS", showKeyboard) { showKeyboard = !showKeyboard; touchTick++ }
                    TBtn(
                        if (ctl.directTouch) "MOUSE:DIRECT" else if (ctl.panMode) "MOUSE:PAN" else "MOUSE:PAD",
                    ) {
                        // trackpad -> direct touch -> pan -> trackpad
                        when {
                            !ctl.directTouch && !ctl.panMode -> ctl.directTouch = true
                            ctl.directTouch -> { ctl.directTouch = false; ctl.panMode = true }
                            else -> ctl.panMode = false
                        }
                        touchTick++
                    }
                    TBtn("RES") { panel = Panel.RESOLUTION }
                    TBtn("SCALE") { if (ctl.zoom > 1.01f) ctl.fitToScreen() else ctl.actualSize(); touchTick++ }
                    TBtn("SET") { panel = Panel.SETTINGS }
                    TBtn("INFO") { panel = Panel.INFO }
                    TBtn("PIN", pinned) { pinned = !pinned }
                    TBtn("-") { minimized = true }
                    TBtn("EXIT") { panel = Panel.EXIT }
                }
            }
        }

        Box(Modifier.align(Alignment.Center)) {
            when (panel) {
                Panel.NONE -> Unit
                Panel.SETTINGS -> PanelBox("DESKTOP SETTINGS", { panel = Panel.NONE }) {
                    TBtn("MOUSE MODE: " + if (ctl.directTouch) "DIRECT TOUCH" else "TRACKPAD") {
                        ctl.directTouch = !ctl.directTouch
                        if (ctl.directTouch) ctl.panMode = false
                    }
                    TBtn("ONE-FINGER DRAG PANS VIEW: " + if (ctl.panMode) "ON" else "OFF") {
                        ctl.panMode = !ctl.panMode
                        if (ctl.panMode) ctl.directTouch = false
                    }
                    TBtn("SCALING: FIT") { ctl.fitToScreen() }
                    TBtn("SCALING: 100%") { ctl.actualSize() }
                    PLine("ZOOM: ${(ctl.zoom * 100).roundToInt()}%  (pinch to zoom)")
                    TBtn("TOOLBAR PINNED: " + if (pinned) "YES" else "NO") { pinned = !pinned }
                    TBtn("AUTO KEYBOARD ON TEXT: " + if (ctl.autoKeyboard) "ON" else "OFF") { ctl.autoKeyboard = !ctl.autoKeyboard }
                    PLine("Opens when you tap while the pointer is an I-beam.")
                }
                Panel.RESOLUTION -> PanelBox("RESOLUTION", { panel = Panel.NONE }) {
                    PLine("CURRENT: ${ctl.fbW}x${ctl.fbH}")
                    PLine("Changing it restarts the desktop session.")
                    TBtn("AUTO (SCREEN ASPECT)") { panel = Panel.NONE; vm.restartDesktop(0, 0) }
                    TBtn("1280x720") { panel = Panel.NONE; vm.restartDesktop(1280, 720) }
                    TBtn("1024x768") { panel = Panel.NONE; vm.restartDesktop(1024, 768) }
                    TBtn("800x600") { panel = Panel.NONE; vm.restartDesktop(800, 600) }
                }
                Panel.INFO -> PanelBox("SESSION INFORMATION", { panel = Panel.NONE }) {
                    PLine("SERVER: ${ctl.client.serverName}")
                    PLine("ADDRESS: 127.0.0.1:${DesktopSession.PORT} (this device only)")
                    PLine("AUTH: VNC password (random per installation)")
                    PLine("FRAMEBUFFER: ${ctl.fbW}x${ctl.fbH}")
                    PLine("ZOOM: ${(ctl.zoom * 100).roundToInt()}%")
                    PLine(inputSummary())
                }
                Panel.EXIT -> PanelBox("EXIT DESKTOP", { panel = Panel.NONE }) {
                    TBtn("DISCONNECT (DESKTOP KEEPS RUNNING)") { vm.exitViewer(false) }
                    TBtn("STOP DESKTOP AND EXIT") { vm.exitViewer(true) }
                }
            }
        }

        if (ctl.closed) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xEE300000))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("DESKTOP DISCONNECTED", color = Color.White, fontFamily = FontFamily.Monospace)
                ctl.closedReason?.let { PLine(it) }
                TBtn("RECONNECT") { vm.reconnect() }
                TBtn("EXIT") { vm.exitViewer(false) }
            }
        }
    }
}
