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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import linuxboot.R
import linuxboot.firmware.FirmwareViewModel
import linuxboot.ui.AppButton
import linuxboot.ui.Btn
import linuxboot.ui.Ico
import kotlin.math.abs
import kotlin.math.roundToInt

private const val LEFT = 1
private const val MIDDLE = 2
private const val RIGHT = 4

private enum class Sheet { NONE, SETTINGS, RESOLUTION, INFO, EXIT }

// The viewer floats over someone else's desktop, so its chrome is a fixed dark glass
// regardless of the app theme.
private val Glass = Color(0xE6161B22)
private val GlassPanel = Color(0xF2161B22)
private val Ink = Color(0xFFE6EAF0)
private val Muted = Color(0xFF9AA6B6)
private val Accent = Color(0xFF8AB4FF)
private val OnAccent = Color(0xFF0A2257)
private val Keycap = Color(0xFF2A3341)
private val KeycapOnce = Color(0xFF3B5EA8)

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
    return "Keyboards connected: $kb - Mice connected: $mice"
}

// ---- viewer chrome ----------------------------------------------------------------------

@Composable
private fun GlassBtn(icon: Int, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (active) Accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Ico(icon, size = 22.dp, tint = if (active) OnAccent else Ink, description = label)
    }
}

@Composable
private fun GlassSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .widthIn(max = 440.dp)
            .heightIn(max = 330.dp)
            .padding(12.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(GlassPanel)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            GlassBtn(R.drawable.ic_close, "Close", onClick = onClose)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun GlassLine(text: String, muted: Boolean = false) {
    Text(text, color = if (muted) Muted else Ink, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun GlassOption(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Keycap)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { Text(label, color = Ink, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun KeyCap(label: String, state: ModState = ModState.OFF, wide: Boolean = false, onClick: () -> Unit) {
    val bg = when (state) {
        ModState.OFF -> Keycap
        ModState.ONCE -> KeycapOnce
        ModState.LOCKED -> Accent
    }
    Box(
        Modifier
            .padding(2.dp)
            .height(40.dp)
            .widthIn(min = if (wide) 64.dp else 44.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (state == ModState.LOCKED) OnAccent else Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun KeyboardPanel(ctl: ViewerController, onSoft: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Glass)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Ctrl" to Keysyms.CTRL, "Alt" to Keysyms.ALT, "Shift" to Keysyms.SHIFT, "Super" to Keysyms.SUPER)
                .forEach { (label, sym) ->
                    val st = ctl.mods[sym] ?: ModState.OFF
                    KeyCap(label, st, wide = true) { ctl.cycleModifier(sym) }
                }
            KeyCap("Text", wide = true) { onSoft() }
            KeyCap("Esc") { ctl.tap(Keysyms.ESCAPE) }
            KeyCap("Tab") { ctl.tap(Keysyms.TAB) }
            KeyCap("Del") { ctl.tap(Keysyms.DELETE) }
            KeyCap("Ins") { ctl.tap(Keysyms.INSERT) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            KeyCap("Home") { ctl.tap(Keysyms.HOME) }
            KeyCap("End") { ctl.tap(Keysyms.END) }
            KeyCap("PgUp") { ctl.tap(Keysyms.PAGE_UP) }
            KeyCap("PgDn") { ctl.tap(Keysyms.PAGE_DOWN) }
            KeyCap("Left", wide = true) { ctl.tap(Keysyms.LEFT) }
            KeyCap("Up") { ctl.tap(Keysyms.UP) }
            KeyCap("Down", wide = true) { ctl.tap(Keysyms.DOWN) }
            KeyCap("Right", wide = true) { ctl.tap(Keysyms.RIGHT) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (i in 1..12) KeyCap("F$i") { ctl.tap(Keysyms.F1 + i - 1) }
        }
    }
}

@Composable
fun ViewerScreen(vm: FirmwareViewModel) {
    // The desktop is landscape and full screen: lock to landscape (either direction, following the
    // sensor) and hide the system bars while it is open; restore both when it closes. The activity
    // handles orientation changes itself, so rotating does not restart the session.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previous
        }
    }

    val ctl = vm.viewer
    if (ctl == null) {
        BackHandler { vm.exitViewer(false) }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("No active desktop", color = Ink)
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
    var sheet by remember { mutableStateOf(Sheet.NONE) }
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
    LaunchedEffect(touchTick, pinned, toolbarVisible, sheet, showKeyboard) {
        if (toolbarVisible && !pinned && sheet == Sheet.NONE && !showKeyboard) {
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
            KeyboardPanel(
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
                    .padding(top = 6.dp)
                    .size(56.dp, 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x99FFFFFF))
                    .clickable { toolbarVisible = true; touchTick++ },
            )
        } else {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(tbOffset.x.roundToInt(), tbOffset.y.roundToInt()) }
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Glass)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(
                    R.drawable.ic_drag,
                    tint = Muted,
                    description = "Move toolbar",
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
                    GlassBtn(R.drawable.ic_plus, "Expand toolbar") { minimized = false; touchTick++ }
                } else {
                    GlassBtn(R.drawable.ic_keyboard, "Keyboard", showKeyboard) { showKeyboard = !showKeyboard; touchTick++ }
                    GlassBtn(
                        when {
                            ctl.directTouch -> R.drawable.ic_pointer
                            ctl.panMode -> R.drawable.ic_move
                            else -> R.drawable.ic_mouse
                        },
                        "Mouse mode",
                    ) {
                        // trackpad -> direct touch -> pan -> trackpad
                        when {
                            !ctl.directTouch && !ctl.panMode -> ctl.directTouch = true
                            ctl.directTouch -> { ctl.directTouch = false; ctl.panMode = true }
                            else -> ctl.panMode = false
                        }
                        touchTick++
                    }
                    GlassBtn(R.drawable.ic_resolution, "Resolution") { sheet = Sheet.RESOLUTION }
                    GlassBtn(R.drawable.ic_fit, "Scale") {
                        if (ctl.zoom > 1.01f) ctl.fitToScreen() else ctl.actualSize()
                        touchTick++
                    }
                    GlassBtn(R.drawable.ic_tune, "Settings") { sheet = Sheet.SETTINGS }
                    GlassBtn(R.drawable.ic_info, "Session information") { sheet = Sheet.INFO }
                    GlassBtn(R.drawable.ic_pin, "Pin toolbar", pinned) { pinned = !pinned }
                    GlassBtn(R.drawable.ic_minus, "Minimize toolbar") { minimized = true }
                    GlassBtn(R.drawable.ic_power, "Exit desktop") { sheet = Sheet.EXIT }
                }
            }
        }

        Box(Modifier.align(Alignment.Center)) {
            when (sheet) {
                Sheet.NONE -> Unit
                Sheet.SETTINGS -> GlassSheet("Desktop settings", { sheet = Sheet.NONE }) {
                    ToggleRow("Direct touch (cursor jumps to your finger)", ctl.directTouch) {
                        ctl.directTouch = it
                        if (it) ctl.panMode = false
                    }
                    ToggleRow("One-finger drag pans the view", ctl.panMode) {
                        ctl.panMode = it
                        if (it) ctl.directTouch = false
                    }
                    ToggleRow("Open keyboard when tapping text", ctl.autoKeyboard) { ctl.autoKeyboard = it }
                    ToggleRow("Keep the toolbar visible", pinned) { pinned = it }
                    GlassOption("Fit to screen") { ctl.fitToScreen() }
                    GlassOption("Actual size (100%)") { ctl.actualSize() }
                    GlassLine("Zoom: ${(ctl.zoom * 100).roundToInt()}%. Pinch to zoom.", muted = true)
                }
                Sheet.RESOLUTION -> GlassSheet("Resolution", { sheet = Sheet.NONE }) {
                    GlassLine("Current: ${ctl.fbW} x ${ctl.fbH}")
                    GlassLine("Changing it restarts the desktop session.", muted = true)
                    GlassOption("Auto (match screen shape)") { sheet = Sheet.NONE; vm.restartDesktop(0, 0) }
                    GlassOption("1280 x 720") { sheet = Sheet.NONE; vm.restartDesktop(1280, 720) }
                    GlassOption("1024 x 768") { sheet = Sheet.NONE; vm.restartDesktop(1024, 768) }
                    GlassOption("800 x 600") { sheet = Sheet.NONE; vm.restartDesktop(800, 600) }
                }
                Sheet.INFO -> GlassSheet("Session information", { sheet = Sheet.NONE }) {
                    GlassLine("Server: ${ctl.client.serverName}")
                    GlassLine("Address: 127.0.0.1:${DesktopSession.PORT} (this device only)")
                    GlassLine("Protection: VNC password, random per installation")
                    GlassLine("Screen: ${ctl.fbW} x ${ctl.fbH}")
                    GlassLine("Zoom: ${(ctl.zoom * 100).roundToInt()}%")
                    GlassLine(inputSummary())
                }
                Sheet.EXIT -> GlassSheet("Leave the desktop", { sheet = Sheet.NONE }) {
                    AppButton("Disconnect, keep it running", { vm.exitViewer(false) }, Modifier.fillMaxWidth(), style = Btn.Tonal)
                    AppButton("Stop the desktop and exit", { vm.exitViewer(true) }, Modifier.fillMaxWidth(), style = Btn.Danger)
                }
            }
        }

        if (ctl.closed) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 420.dp)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xF2401414))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Desktop disconnected", color = Ink, style = MaterialTheme.typography.titleMedium)
                ctl.closedReason?.let { GlassLine(it, muted = true) }
                AppButton("Reconnect", { vm.reconnect() }, Modifier.fillMaxWidth())
                AppButton("Exit", { vm.exitViewer(false) }, Modifier.fillMaxWidth(), style = Btn.Outlined)
            }
        }
    }
}
