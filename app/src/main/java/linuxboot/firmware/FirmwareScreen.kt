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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import linuxboot.desktop.ViewerScreen
import linuxboot.distro.Catalog
import linuxboot.distro.DesktopInstaller
import linuxboot.distro.DistroState
import linuxboot.distro.DownloadProgress
import linuxboot.distro.Phase
import linuxboot.runtime.NativeRuntime
import linuxboot.storage.LinuxBootStorage
import linuxboot.storage.ProbeResult
import linuxboot.storage.RootfsLocation
import linuxboot.system.SystemInfo
import linuxboot.system.formatBytes

private val Bg = Color(0xFF0000AA)
private val Fg = Color(0xFFE6E6E6)
private val Dim = Color(0xFF9FB0FF)
private val SelBg = Color(0xFFC0C0C0)

private class MenuItem(val label: String, val enabled: Boolean = true, val action: () -> Unit)

/** Lines starting with "# " render as bold section headers. */
private class Model(
    val title: String,
    val lines: List<String>,
    val itemsHeader: String? = null,
    val items: List<MenuItem> = emptyList(),
)

private fun passFail(b: Boolean) = if (b) "PASS" else "FAIL"

private fun locText(l: RootfsLocation) =
    if (l == RootfsLocation.EXTERNAL) "EXTERNAL APP-SPECIFIC STORAGE"
    else "INTERNAL APP STORAGE (external failed the Linux filesystem test)"

private fun probeLines(p: ProbeResult): List<String> = listOf(
    "SYMBOLIC LINKS: ${passFail(p.symlink)}",
    "PERMISSION BITS (CHMOD): ${passFail(p.chmod)}",
    "HARD LINKS: ${passFail(p.hardlink)}",
    "CASE-SENSITIVE NAMES: ${passFail(p.caseSensitive)}",
    "EXEC FROM STORAGE (INFO ONLY): " + if (p.canExec) "YES" else "NO",
) + p.errors.map { "  ! $it" }

private fun progressLine(p: DownloadProgress): String {
    val pct = if (p.total > 0) " (${p.downloaded * 100 / p.total}%)" else ""
    val tot = if (p.total > 0) formatBytes(p.total) else "?"
    return "${formatBytes(p.downloaded)} / $tot$pct  ${formatBytes(p.bytesPerSec)}/s"
}

private fun runtimeText(vm: FirmwareViewModel) = when {
    !NativeRuntime.available -> "NOT INSTALLED (Rust library missing)"
    vm.proot.available -> "READY (RUST + PROOT)"
    else -> "RUST READY, PROOT MISSING"
}

private fun systemLines(i: SystemInfo, vm: FirmwareViewModel): List<String> {
    val l = mutableListOf(
        "# SYSTEM INFORMATION",
        "CPU: " + (if (i.isArm64) "ARM64" else i.abis.firstOrNull() ?: "UNKNOWN"),
        "ARCHITECTURE: " + i.arch.uppercase(),
        "MEMORY: ${formatBytes(i.totalMemBytes)} (${formatBytes(i.availMemBytes)} FREE)",
        "STORAGE: ${formatBytes(i.storageFreeBytes)} FREE OF ${formatBytes(i.storageTotalBytes)}",
        "RUNTIME: ${runtimeText(vm)}",
    )
    if (!i.isArm64) l += "WARNING: NO ARM64 ABI. NATIVE LINUX UNSUPPORTED."
    return l
}

private fun hardwareLines(i: SystemInfo, vm: FirmwareViewModel) = listOf(
    "DEVICE: ${i.device}",
    "ANDROID: ${i.androidRelease} (API ${i.sdk})",
    "CPU CORES: ${i.cores}",
    "SUPPORTED ABIS: ${i.abis.joinToString(", ")}",
    "KERNEL ARCH: ${i.arch}",
    "NATIVE ARM64 LINUX: " + if (i.isArm64) "SUPPORTED" else "NOT SUPPORTED",
    "RAM TOTAL: ${formatBytes(i.totalMemBytes)}",
    "RAM AVAILABLE: ${formatBytes(i.availMemBytes)}",
    "LOW MEMORY: " + if (i.lowMemory) "YES" else "NO",
    "LINUXBOOT STORAGE FREE: ${formatBytes(LinuxBootStorage.freeBytes(vm.storage.dataRoot))}",
)

private fun model(vm: FirmwareViewModel, info: SystemInfo, onShutdown: () -> Unit): Model =
    when (val s = vm.screen) {
        Screen.Home -> Model(
            "", systemLines(info, vm), "BOOT OPTIONS",
            listOf(
                MenuItem("Boot Operating System") { vm.open(Screen.Boot) },
                MenuItem("Install Operating System") { vm.open(Screen.InstallList) },
                MenuItem("Boot Manager") { vm.open(Screen.BootManager) },
                MenuItem("System Configuration") { vm.open(Screen.Config) },
                MenuItem("Hardware Information") { vm.open(Screen.Hardware) },
                MenuItem("Recovery Environment") { vm.open(Screen.Recovery) },
                MenuItem("Shutdown Application") { onShutdown() },
            ),
        )
        Screen.Viewer -> Model("", emptyList())
        Screen.Hardware -> Model("HARDWARE INFORMATION", hardwareLines(info, vm))
        Screen.Boot -> {
            val ready = vm.installed.filter { it.state == DistroState.EXTRACTED }
            val bootable = ready.filter { it.desktop != null }
            Model(
                "BOOT OPERATING SYSTEM",
                listOfNotNull(
                    if (ready.isEmpty()) "NO BOOTABLE DISTRIBUTION FOUND. Use Install Operating System." else null,
                    if (ready.isNotEmpty() && bootable.isEmpty()) "No desktop installed yet. Open Boot Manager > distribution > Install desktop." else null,
                    if (!vm.proot.available) "PROOT UNAVAILABLE: ${vm.proot.problem}" else null,
                    vm.desktopBusy?.let { "$it..." },
                    vm.notice,
                ) + vm.desktopStatus.takeLast(12),
                if (bootable.isEmpty()) null else "BOOT",
                bootable.map { m ->
                    MenuItem("Boot ${m.name}", vm.proot.available && vm.desktopBusy == null) { vm.bootDesktop(m.id) }
                },
            )
        }
        Screen.InstallList -> Model(
            "INSTALL OPERATING SYSTEM",
            listOfNotNull(
                "LinuxBoot downloads, verifies and extracts the distribution for you.",
                vm.installingId?.let { "INSTALLING: $it (open it to see progress)" },
            ),
            "DISTRIBUTIONS",
            Catalog.entries.map { e ->
                val m = vm.installed.firstOrNull { it.id == e.id }
                val st = if (vm.installingId == e.id) "INSTALLING" else m?.state?.label ?: "NOT INSTALLED"
                MenuItem("${e.name}  [$st]") { vm.open(Screen.InstallDetail(e.id)) }
            },
        )
        is Screen.InstallDetail -> installDetail(vm, s.id)
        Screen.BootManager -> Model(
            "BOOT MANAGER",
            listOfNotNull(
                "REGISTERED: ${vm.installed.size}",
                "STORAGE ROOT: ${vm.storage.dataRoot.path}",
                vm.notice,
                if (vm.installed.isEmpty()) "NO DISTRIBUTIONS REGISTERED" else null,
            ),
            if (vm.installed.isEmpty()) null else "DISTRIBUTIONS",
            vm.installed.map { m ->
                MenuItem("${m.name} ${m.version}  [${m.state.label}]  ${formatBytes(vm.usage[m.id] ?: 0L)}") {
                    vm.open(Screen.DistroDetail(m.id))
                }
            },
        )
        is Screen.DistroDetail -> {
            val m = vm.installed.firstOrNull { it.id == s.id }
            if (m == null) Model("DISTRIBUTION", listOf("NOT FOUND")) else {
                val shell = vm.shellText?.let { listOf("", "# SHELL TEST") + it.lines().take(40) } ?: emptyList()
                val desk = if (vm.desktopStatus.isEmpty()) emptyList() else listOf("", "# DESKTOP LOG") + vm.desktopStatus.takeLast(15)
                val ready = m.state == DistroState.EXTRACTED && vm.proot.available
                val idle = vm.desktopBusy == null
                val hasDesktop = m.desktop != null
                Model(
                    m.name.uppercase(),
                    listOfNotNull(
                        "VERSION: ${m.version}",
                        "STATE: ${m.state.label}",
                        m.message?.let { "LAST ERROR: $it" },
                        "LOCATION: ${locText(m.location)}",
                        "ROOTFS: ${m.rootfsPath}",
                        "DISK USAGE: ${formatBytes(vm.usage[m.id] ?: 0L)}",
                        "SOURCE: ${m.sourceUrl}",
                        "SHA-256: ${m.sha256}",
                        "",
                        "DESKTOP: " + when {
                            hasDesktop -> "XFCE INSTALLED"
                            DesktopInstaller.supports(m.id) -> "NOT INSTALLED"
                            else -> "NO VERIFIED RECIPE FOR THIS DISTRIBUTION YET"
                        },
                        "SESSION: " + if (vm.sessionRunning) "RUNNING" else "STOPPED",
                        "BACKUPS: ${vm.backups[m.id]?.size ?: 0}" +
                            (vm.backups[m.id]?.firstOrNull()?.let { " (newest: ${it.name}, ${formatBytes(it.length())})" } ?: ""),
                        vm.desktopBusy?.let { "$it..." },
                        vm.opBusy?.let { "$it... (no progress is available for this step)" },
                        vm.notice,
                        if (!vm.proot.available) "PROOT: ${vm.proot.problem}" else null,
                    ) + shell + desk,
                    "ACTIONS",
                    listOf(
                        MenuItem("Start desktop", ready && idle && hasDesktop) { vm.bootDesktop(m.id) },
                        MenuItem("Stop desktop", vm.sessionRunning && idle) { vm.stopDesktop() },
                        MenuItem(
                            "Install desktop (XFCE, ~1 GB)",
                            ready && idle && !hasDesktop && DesktopInstaller.supports(m.id),
                        ) { vm.installDesktop(m.id) },
                        MenuItem("Run shell test (PRoot)", ready && !vm.shellRunning) { vm.runShellTest(m.id) },
                        MenuItem(
                            "Back up rootfs",
                            m.state == DistroState.EXTRACTED && idle && vm.opBusy == null && !vm.sessionRunning,
                        ) { vm.backupDistro(m.id) },
                        MenuItem(
                            "Restore newest backup",
                            m.state == DistroState.EXTRACTED && idle && vm.opBusy == null && !vm.sessionRunning &&
                                vm.backups[m.id]?.isNotEmpty() == true,
                        ) { vm.open(Screen.ConfirmRestore(m.id)) },
                        MenuItem("Remove distribution", vm.installingId != m.id && !vm.sessionRunning && vm.opBusy == null) {
                            vm.open(Screen.ConfirmRemove(m.id))
                        },
                    ),
                )
            }
        }
        is Screen.ConfirmRemove -> {
            val m = vm.installed.firstOrNull { it.id == s.id }
            Model(
                "CONFIRM REMOVAL",
                listOf(
                    "Remove ${m?.name ?: s.id}?",
                    "This deletes its rootfs, home, logs and metadata",
                    "and frees ${formatBytes(vm.usage[s.id] ?: 0L)}. It cannot be undone.",
                ),
                null,
                listOf(
                    MenuItem("Confirm removal") { vm.remove(s.id) },
                    MenuItem("Cancel") { vm.back() },
                ),
            )
        }
        is Screen.ConfirmRestore -> {
            val m = vm.installed.firstOrNull { it.id == s.id }
            val b = vm.backups[s.id]?.firstOrNull()
            Model(
                "CONFIRM RESTORE",
                listOf(
                    "Replace ${m?.name ?: s.id}'s rootfs with:",
                    b?.name ?: "(no backup)",
                    "Changes made after that backup are lost.",
                    "The current rootfs is kept until the restore succeeds.",
                ),
                null,
                listOf(
                    MenuItem("Confirm restore", b != null) { vm.restoreDistro(s.id) },
                    MenuItem("Cancel") { vm.back() },
                ),
            )
        }
        Screen.Config -> {
            val d = vm.decision
            val l = mutableListOf(
                "# STORAGE",
                "DATA ROOT: ${vm.storage.dataRoot.path}",
                "DATA ROOT TYPE: " +
                    if (vm.storage.isExternal) "EXTERNAL APP-SPECIFIC" else "INTERNAL (EXTERNAL UNAVAILABLE)",
            )
            if (d == null) {
                l += "STORAGE TEST: NOT RUN YET (runs automatically on first install)"
                vm.storage.decisionProblem?.let { l += "SAVED RESULT UNREADABLE: $it" }
            } else {
                l += "ROOTFS PLACEMENT: ${locText(d.location)}"
                l += probeLines(d.probe)
            }
            l += ""
            l += "# RUNTIME"
            l += "RUST LIBRARY: " + if (NativeRuntime.available) "LOADED" else "MISSING (${NativeRuntime.unavailableReason})"
            l += "PROOT: " + (vm.proot.problem?.let { "MISSING - $it" } ?: "PRESENT")
            l += ""
            l += "# PERMISSIONS"
            l += "INTERNET (HTTPS rootfs downloads only)"
            Model("SYSTEM CONFIGURATION", l, "ACTIONS", listOf(MenuItem("Run storage test now") { vm.rerunProbe() }))
        }
        Screen.Recovery -> Model(
            "RECOVERY ENVIRONMENT",
            listOfNotNull(
                "RECOVERY SHELL: not implemented yet",
                "",
                "PARTIAL DOWNLOADS AND CACHE: ${formatBytes(vm.cacheBytes)}",
                vm.notice,
            ),
            "ACTIONS",
            listOf(MenuItem("Clear partial downloads and cache", vm.installingId == null) { vm.clearDownloads() }),
        )
    }

private fun installDetail(vm: FirmwareViewModel, id: String): Model {
    val entry = Catalog.find(id) ?: return Model("UNKNOWN DISTRIBUTION", listOf(id))
    val lines = mutableListOf(entry.note)

    if (vm.installingId == id) {
        lines += ""
        lines += "# PROGRESS"
        vm.status?.let { st ->
            lines += "STEP: ${st.phase.label}"
            if (st.detail.isNotBlank()) lines += st.detail
            st.progress?.let { lines += progressLine(it) }
        }
        return Model(entry.name.uppercase(), lines, null, listOf(MenuItem("Cancel installation") { vm.cancelInstall() }))
    }

    val existing = vm.installed.firstOrNull { it.id == id }
    if (existing?.state == DistroState.EXTRACTED) {
        lines += ""
        lines += "ALREADY INSTALLED: version ${existing.version}"
        lines += "Manage it from Boot Manager."
        return Model(entry.name.uppercase(), lines)
    }

    existing?.let {
        lines += ""
        lines += "PREVIOUS ATTEMPT: ${it.state.label}" + (it.message?.let { m -> " - $m" } ?: "")
    }
    vm.status?.let { st ->
        if (st.phase == Phase.FAILED || st.phase == Phase.CANCELLED || st.phase == Phase.DONE) {
            lines += "LAST RESULT: ${st.phase.label} ${st.detail}"
        }
    }
    lines += ""

    return when (val src = vm.source) {
        SourceState.Idle, SourceState.Loading -> {
            lines += "RESOLVING SOURCE..."
            Model(entry.name.uppercase(), lines)
        }
        is SourceState.Error -> {
            lines += "SOURCE ERROR: ${src.message}"
            Model(entry.name.uppercase(), lines, null, listOf(MenuItem("Retry") { vm.retryResolve(id) }))
        }
        is SourceState.Ready -> {
            val s = src.src
            lines += "VERSION: ${s.version}"
            lines += "DOWNLOAD SIZE: ${formatBytes(s.sizeBytes)}"
            lines += "EXTRACTED SIZE (ESTIMATE): up to ${formatBytes(s.estimatedInstalledBytes)}"
            lines += "SHA-256: ${s.sha256}"
            val d = vm.decision
            lines += "ROOTFS PLACEMENT: " + (d?.let { locText(it.location) } ?: "decided by a filesystem test when you press Install")
            lines += "DOWNLOADS: ${vm.storage.downloads.path}"
            val label = if (existing?.state == DistroState.INSTALLING) "Install (resume)" else "Install"
            Model(entry.name.uppercase(), lines, null, listOf(MenuItem(label) { vm.startInstall(id) }))
        }
    }
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

@Composable
fun FirmwareApp(@Suppress("UNUSED_PARAMETER") initialInfo: SystemInfo, onShutdown: () -> Unit) {
    val vm: FirmwareViewModel = viewModel()
    val context = LocalContext.current
    // Re-read real RAM/storage figures each time the screen changes instead of showing launch-time values.
    val info = remember(vm.screen) { SystemInfo.detect(context) }
    if (vm.screen == Screen.Viewer) {
        ViewerScreen(vm)
        return
    }
    var selected by remember { mutableIntStateOf(0) }
    var help by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(vm.screen) { selected = 0 }

    val m = model(vm, info, onShutdown)
    val sel = if (m.items.isEmpty()) -1 else selected.coerceIn(0, m.items.lastIndex)

    fun activate() {
        if (sel >= 0 && m.items[sel].enabled) m.items[sel].action()
    }
    fun back() {
        if (help) help = false else vm.back()
    }

    BackHandler(enabled = vm.screen != Screen.Home || help) { back() }

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
                        if (!help && m.items.isNotEmpty()) selected = (sel + m.items.size - 1) % m.items.size
                        true
                    }
                    Key.DirectionDown -> {
                        if (!help && m.items.isNotEmpty()) selected = (sel + 1) % m.items.size
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        if (!help) activate()
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
            if (help) {
                Line("HELP", bold = true)
                Spacer(Modifier.height(8.dp))
                Line("UP / DOWN : MOVE SELECTION")
                Line("ENTER     : SELECT")
                Line("ESC       : BACK")
                Line("F1        : TOGGLE HELP")
                Line("TOUCH     : TAP AN ENTRY")
            } else {
                if (m.title.isNotBlank()) {
                    Line(m.title, bold = true)
                    Spacer(Modifier.height(8.dp))
                }
                m.lines.forEach {
                    if (it.startsWith("# ")) Line(it.removePrefix("# "), bold = true) else Line(it)
                }
                if (m.items.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    m.itemsHeader?.let {
                        Line(it, bold = true)
                        Spacer(Modifier.height(4.dp))
                    }
                    m.items.forEachIndexed { idx, item ->
                        val isSel = idx == sel
                        Text(
                            text = (if (isSel) "> " else "  ") + item.label,
                            color = when {
                                isSel -> Bg
                                item.enabled -> Fg
                                else -> Dim
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSel) SelBg else Color.Transparent)
                                .clickable(enabled = item.enabled) {
                                    selected = idx
                                    item.action()
                                }
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
                modifier = Modifier.clickable { if (!help) activate() }.padding(vertical = 8.dp),
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
