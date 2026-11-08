package linuxboot.firmware

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import linuxboot.system.SystemInfo
import linuxboot.system.formatBytes

private val Bg = Color(0xFF0000AA)
private val Fg = Color(0xFFE6E6E6)
private val Dim = Color(0xFF9FB0FF)
private val SelBg = Color(0xFFC0C0C0)

private enum class Entry(val label: String) {
    BOOT("Boot Operating System"),
    INSTALL("Install Operating System"),
    BOOT_MANAGER("Boot Manager"),
    CONFIG("System Configuration"),
    HARDWARE("Hardware Information"),
    RECOVERY("Recovery Environment"),
    SHUTDOWN("Shutdown Application"),
}

@Composable
private fun Line(text: String, color: Color = Fg, bold: Boolean = false) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    )
}

private fun systemLines(i: SystemInfo): List<String> = listOf(
    "CPU: " + (if (i.isArm64) "ARM64" else i.abis.firstOrNull() ?: "UNKNOWN"),
    "ARCHITECTURE: " + i.arch.uppercase(),
    "MEMORY: ${formatBytes(i.totalMemBytes)} (${formatBytes(i.availMemBytes)} FREE)",
    "STORAGE: ${formatBytes(i.storageFreeBytes)} FREE OF ${formatBytes(i.storageTotalBytes)}",
    "RUNTIME: " + if (i.runtimeInstalled) "READY" else "NOT INSTALLED",
)

private fun detailLines(e: Entry, i: SystemInfo): List<String> = when (e) {
    Entry.HARDWARE -> listOf(
        "DEVICE: ${i.device}",
        "ANDROID: ${i.androidRelease} (API ${i.sdk})",
        "CPU CORES: ${i.cores}",
        "SUPPORTED ABIS: ${i.abis.joinToString(", ")}",
        "KERNEL ARCH: ${i.arch}",
        "NATIVE ARM64 LINUX: " + if (i.isArm64) "SUPPORTED" else "NOT SUPPORTED",
        "RAM TOTAL: ${formatBytes(i.totalMemBytes)}",
        "RAM AVAILABLE: ${formatBytes(i.availMemBytes)}",
        "LOW MEMORY: " + if (i.lowMemory) "YES" else "NO",
        "APP STORAGE FREE: ${formatBytes(i.storageFreeBytes)}",
    )
    Entry.CONFIG -> listOf(
        "APP DATA DIRECTORY: ${i.filesPath}",
        "RUNTIME DIRECTORY: ${i.filesPath}/runtime",
        "RUNTIME STATUS: " + if (i.runtimeInstalled) "PRESENT" else "NOT INSTALLED",
        "ANDROID PERMISSIONS REQUESTED: NONE",
        "BOOT ENTRIES: 0",
        "",
        "Read-only in this build. Editable settings arrive with the runtime (Phase 2).",
    )
    Entry.BOOT -> listOf(
        "NO BOOTABLE DISTRIBUTION FOUND",
        "",
        "Nothing is installed, and the Linux runtime is not part of this build yet (Phase 2).",
    )
    Entry.INSTALL -> listOf(
        "INSTALLER UNAVAILABLE",
        "",
        "Requires the Rust runtime and PRoot (Phase 2).",
    )
    Entry.BOOT_MANAGER -> listOf(
        "BOOT MANAGER: 0 ENTRIES",
        "",
        "No distributions are registered.",
    )
    Entry.RECOVERY -> listOf(
        "RECOVERY ENVIRONMENT UNAVAILABLE",
        "",
        "No runtime is installed, so there is nothing to recover.",
    )
    Entry.SHUTDOWN -> emptyList()
}

@Composable
fun FirmwareApp(info: SystemInfo, onShutdown: () -> Unit) {
    val entries = Entry.entries
    var selected by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Entry?>(null) }
    var help by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun activate(e: Entry) {
        if (e == Entry.SHUTDOWN) onShutdown() else open = e
    }
    fun back() {
        if (help) help = false else open = null
    }

    BackHandler(enabled = open != null || help) { back() }

    val atHome = open == null && !help

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            .displayCutoutPadding()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) {
                    false
                } else when (ev.key) {
                    Key.DirectionUp -> {
                        if (atHome) selected = (selected + entries.size - 1) % entries.size
                        true
                    }
                    Key.DirectionDown -> {
                        if (atHome) selected = (selected + 1) % entries.size
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        if (atHome) activate(entries[selected])
                        true
                    }
                    Key.Escape -> { back(); true }
                    Key.F1 -> { help = !help; true }
                    else -> false
                }
            }
            .padding(12.dp),
    ) {
        Line("LINUXBOOT FIRMWARE", bold = true)
        Line("Version 1.0", Dim)
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            val current = open
            when {
                help -> {
                    Line("HELP", bold = true)
                    Spacer(Modifier.height(8.dp))
                    Line("UP / DOWN : MOVE SELECTION")
                    Line("ENTER     : SELECT")
                    Line("ESC       : BACK")
                    Line("F1        : TOGGLE HELP")
                    Line("TOUCH     : TAP AN ENTRY")
                }
                current != null -> {
                    Line(current.label.uppercase(), bold = true)
                    Spacer(Modifier.height(8.dp))
                    detailLines(current, info).forEach { Line(it) }
                }
                else -> {
                    Line("SYSTEM INFORMATION", bold = true)
                    Spacer(Modifier.height(4.dp))
                    systemLines(info).forEach { Line(it) }
                    if (!info.isArm64) {
                        Spacer(Modifier.height(4.dp))
                        Line("WARNING: NO ARM64 ABI. NATIVE LINUX UNSUPPORTED.", Color.Yellow)
                    }
                    Spacer(Modifier.height(16.dp))
                    Line("BOOT OPTIONS", bold = true)
                    Spacer(Modifier.height(4.dp))
                    entries.forEachIndexed { idx, e ->
                        val sel = idx == selected
                        Text(
                            text = (if (sel) "> " else "  ") + e.label,
                            color = if (sel) Bg else Fg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (sel) SelBg else Color.Transparent)
                                .clickable { selected = idx; activate(e) }
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                "ENTER: SELECT", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier
                    .clickable { if (atHome) activate(entries[selected]) }
                    .padding(vertical = 8.dp),
            )
            Text(
                "ESC: BACK", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.clickable { back() }.padding(vertical = 8.dp),
            )
            Text(
                "F1: HELP", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.clickable { help = !help }.padding(vertical = 8.dp),
            )
        }
    }
}
