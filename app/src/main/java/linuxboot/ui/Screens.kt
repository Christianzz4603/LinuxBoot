package linuxboot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import linuxboot.R
import linuxboot.distro.Catalog
import linuxboot.distro.DesktopInstaller
import linuxboot.distro.DistroMetadata
import linuxboot.distro.DistroState
import linuxboot.distro.DownloadProgress
import linuxboot.distro.Phase
import linuxboot.firmware.FirmwareViewModel
import linuxboot.firmware.Screen
import linuxboot.firmware.SourceState
import linuxboot.runtime.NativeRuntime
import linuxboot.storage.LinuxBootStorage
import linuxboot.storage.ProbeResult
import linuxboot.storage.RootfsLocation
import linuxboot.system.SystemInfo
import linuxboot.system.formatBytes
import linuxboot.ui.theme.LocalExtraColors

// ---- small helpers ----------------------------------------------------------------------

private fun Phase.title(): String = when (this) {
    Phase.PREPARING -> "Checking this device"
    Phase.RESOLVING -> "Finding the latest release"
    Phase.DOWNLOADING -> "Downloading"
    Phase.VERIFYING -> "Verifying the checksum"
    Phase.EXTRACTING -> "Unpacking files"
    Phase.CONFIGURING -> "Configuring"
    Phase.DONE -> "Done"
    Phase.FAILED -> "Failed"
    Phase.CANCELLED -> "Cancelled"
}

private val installSteps = listOf(
    Phase.PREPARING, Phase.RESOLVING, Phase.DOWNLOADING, Phase.VERIFYING, Phase.EXTRACTING, Phase.CONFIGURING,
)

private fun locText(l: RootfsLocation) =
    if (l == RootfsLocation.EXTERNAL) "Shared app storage" else "Internal app storage"

private fun stateTone(s: DistroState) = when (s) {
    DistroState.EXTRACTED -> Tone.Good
    DistroState.INSTALLING -> Tone.Warn
    DistroState.FAILED -> Tone.Bad
}

private fun progressText(p: DownloadProgress): String {
    val total = if (p.total > 0) formatBytes(p.total) else "?"
    return "${formatBytes(p.downloaded)} of $total - ${formatBytes(p.bytesPerSec)}/s"
}

@Composable
private fun NoticeBanner(text: String) {
    val u = text.uppercase()
    val tone = when {
        "FAIL" in u -> Tone.Bad
        "NOT ENOUGH" in u || "STOP THE" in u || "CANNOT" in u || u.startsWith("NO ") -> Tone.Warn
        else -> Tone.Good
    }
    Banner(tone, text.friendly())
}

@Composable
private fun Meter(icon: Int, label: String, used: Long, total: Long) {
    val c = MaterialTheme.colorScheme
    val frac = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ico(icon, size = 18.dp, tint = c.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${formatBytes(used)} of ${formatBytes(total)}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            trackColor = c.surfaceVariant,
        )
    }
}

@Composable
private fun BusyPanel(title: String, lines: List<String>?) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        if (lines != null && lines.isNotEmpty()) LogPanel(lines.takeLast(12), maxHeight = 180.dp)
    }
}

/** One installed system. Boots straight into the desktop when one is installed. */
@Composable
private fun SystemCard(vm: FirmwareViewModel, m: DistroMetadata, showUsage: Boolean) {
    val ready = m.state == DistroState.EXTRACTED
    val c = MaterialTheme.colorScheme
    Panel(onClick = { vm.open(Screen.DistroDetail(m.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DistroBadge(m.id, m.name)
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium)
                val extra = if (showUsage) " - ${formatBytes(vm.usage[m.id] ?: 0L)}" else ""
                Text(m.version + extra, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            StatusChip(m.state.label.sentence(), stateTone(m.state))
        }
        if (ready) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (m.desktop != null) {
                    AppButton(
                        "Boot", { vm.bootDesktop(m.id) },
                        Modifier.weight(1f), R.drawable.ic_play,
                        enabled = vm.proot.available && vm.desktopBusy == null && vm.opBusy == null,
                    )
                } else {
                    AppButton(
                        "Set up desktop", { vm.open(Screen.DistroDetail(m.id)) },
                        Modifier.weight(1f), R.drawable.ic_monitor, style = Btn.Tonal,
                    )
                }
                AppButton("Details", { vm.open(Screen.DistroDetail(m.id)) }, style = Btn.Outlined)
            }
        }
    }
}

// ---- Home -------------------------------------------------------------------------------

@Composable
fun HomeScreen(vm: FirmwareViewModel, info: SystemInfo) {
    val c = MaterialTheme.colorScheme
    Page(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0000AA)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(46.dp), contentScale = ContentScale.Fit)
            }
            Column(Modifier.weight(1f)) {
                Text("LinuxBoot", style = MaterialTheme.typography.headlineSmall)
                Text("Linux on your phone. No root, no emulation.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }

        when {
            !NativeRuntime.available -> Banner(Tone.Bad, "The native library is missing: ${NativeRuntime.unavailableReason}")
            !vm.proot.available -> Banner(Tone.Bad, "PRoot is unavailable: ${vm.proot.problem}")
            else -> Row { StatusChip("Runtime ready", Tone.Good, R.drawable.ic_check) }
        }
        vm.notice?.let { NoticeBanner(it) }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ico(R.drawable.ic_cpu, tint = c.primary)
                Column {
                    Text("This device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${info.device} - ${info.arch.uppercase()} - ${info.cores} cores",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    )
                }
            }
            Meter(R.drawable.ic_memory, "Memory in use", (info.totalMemBytes - info.availMemBytes).coerceAtLeast(0L), info.totalMemBytes)
            Meter(R.drawable.ic_storage, "Storage in use", (info.storageTotalBytes - info.storageFreeBytes).coerceAtLeast(0L), info.storageTotalBytes)
            if (!info.isArm64) Banner(Tone.Bad, "This device does not report an ARM64 ABI, so native ARM64 Linux is not supported.")
        }

        vm.desktopBusy?.let { BusyPanel(it.sentence() + "...", vm.desktopStatus) }
        vm.opBusy?.let { BusyPanel(it.sentence() + "...", null) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your systems", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            AppButton("Install", { vm.open(Screen.InstallList) }, icon = R.drawable.ic_plus, style = Btn.Text)
        }
        if (vm.installed.isEmpty()) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Ico(R.drawable.ic_layers, tint = c.onSurfaceVariant, size = 28.dp)
                    Column {
                        Text("No systems yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Install Debian, Ubuntu, Alpine and more. LinuxBoot downloads and sets everything up.",
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        )
                    }
                }
                AppButton("Browse systems", { vm.open(Screen.InstallList) }, Modifier.fillMaxWidth(), R.drawable.ic_download)
            }
        } else {
            vm.installed.forEach { SystemCard(vm, it, showUsage = false) }
        }
    }
}

// ---- Install ----------------------------------------------------------------------------

@Composable
fun InstallListScreen(vm: FirmwareViewModel) {
    val c = MaterialTheme.colorScheme
    Page(Modifier.fillMaxSize()) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Install a system", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Pick a distribution. LinuxBoot downloads, verifies and unpacks it for you.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            )
        }
        vm.installingId?.let { id ->
            Banner(Tone.Accent, "Installing ${Catalog.find(id)?.name ?: id}. Open it to see progress.")
        }
        Catalog.entries.forEach { e ->
            val m = vm.installed.firstOrNull { it.id == e.id }
            val (label, tone) = when {
                vm.installingId == e.id -> "Installing" to Tone.Accent
                m != null -> m.state.label.sentence() to stateTone(m.state)
                else -> "Not installed" to Tone.Neutral
            }
            Panel(onClick = { vm.open(Screen.InstallDetail(e.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DistroBadge(e.id, e.name)
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.titleMedium)
                        Text(e.note, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    StatusChip(label, tone)
                }
            }
        }
    }
}

@Composable
private fun PhaseSteps(current: Phase?) {
    val c = MaterialTheme.colorScheme
    val idx = when {
        current == null -> 0
        current == Phase.DONE -> installSteps.size
        else -> installSteps.indexOf(current).coerceAtLeast(0)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        installSteps.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    i < idx -> Ico(R.drawable.ic_check, size = 18.dp, tint = LocalExtraColors.current.success)
                    i == idx -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else -> Box(Modifier.size(18.dp).border(1.5.dp, c.outline, CircleShape))
                }
                Text(
                    p.title(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (i > idx) c.onSurfaceVariant else c.onSurface,
                )
            }
        }
    }
}

@Composable
fun InstallDetailScreen(vm: FirmwareViewModel, id: String) {
    val c = MaterialTheme.colorScheme
    val entry = Catalog.find(id)
    if (entry == null) {
        ScreenBar("Unknown system") { vm.back() }
        return
    }
    val installing = vm.installingId == id
    val existing = vm.installed.firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize()) {
        ScreenBar(entry.name) { vm.back() }
        Page(Modifier.weight(1f)) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DistroBadge(entry.id, entry.name, 56.dp)
                    Text(entry.note, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }

            when {
                installing -> {
                    val st = vm.status
                    Panel {
                        Text(st?.phase?.title() ?: "Starting", style = MaterialTheme.typography.titleMedium)
                        st?.progress?.let { p ->
                            val frac = if (p.total > 0) (p.downloaded.toFloat() / p.total).coerceIn(0f, 1f) else 0f
                            LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                trackColor = c.surfaceVariant,
                            )
                            Text(progressText(p), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        val detail = st?.detail.orEmpty()
                        if (detail.isNotBlank() && st?.progress == null) {
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        PhaseSteps(st?.phase)
                    }
                    AppButton("Cancel installation", { vm.cancelInstall() }, Modifier.fillMaxWidth(), style = Btn.Outlined)
                }

                existing?.state == DistroState.EXTRACTED -> {
                    Banner(Tone.Good, "Installed: version ${existing.version}.")
                    AppButton("Open system", { vm.open(Screen.DistroDetail(id)) }, Modifier.fillMaxWidth(), R.drawable.ic_layers)
                }

                else -> {
                    existing?.let {
                        Banner(
                            if (it.state == DistroState.FAILED) Tone.Bad else Tone.Warn,
                            "Previous attempt: ${it.state.label.sentence()}" + (it.message?.let { m -> ". $m" } ?: ""),
                        )
                    }
                    vm.status?.let { st ->
                        when (st.phase) {
                            Phase.FAILED -> Banner(Tone.Bad, st.detail)
                            Phase.CANCELLED -> Banner(Tone.Warn, st.detail)
                            Phase.DONE -> Banner(Tone.Good, st.detail)
                            else -> Unit
                        }
                    }
                    when (val src = vm.source) {
                        SourceState.Idle, SourceState.Loading -> Panel {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Finding the latest release...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        is SourceState.Error -> {
                            Banner(Tone.Bad, "Could not reach the download source: ${src.message}")
                            AppButton("Try again", { vm.retryResolve(id) }, Modifier.fillMaxWidth(), style = Btn.Tonal)
                        }
                        is SourceState.Ready -> {
                            val s = src.src
                            Panel {
                                SectionTitle("Release")
                                InfoRow("Version", s.version)
                                InfoRow("Download size", formatBytes(s.sizeBytes))
                                InfoRow("Space needed (estimate)", "up to ${formatBytes(s.estimatedInstalledBytes)}")
                                InfoRow(
                                    "Stored in",
                                    vm.decision?.let { locText(it.location) } ?: "Chosen by a storage test when you install",
                                )
                                InfoRow("SHA-256", s.sha256, mono = true)
                            }
                            AppButton(
                                if (existing?.state == DistroState.INSTALLING) "Resume install" else "Install ${entry.name}",
                                { vm.startInstall(id) }, Modifier.fillMaxWidth(), R.drawable.ic_download,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- Systems ----------------------------------------------------------------------------

@Composable
fun SystemsScreen(vm: FirmwareViewModel) {
    val c = MaterialTheme.colorScheme
    Page(Modifier.fillMaxSize()) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Systems", style = MaterialTheme.typography.headlineMedium)
            Text(
                "${vm.installed.size} installed - stored in ${vm.storage.dataRoot.path}",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
            )
        }
        vm.notice?.let { NoticeBanner(it) }
        if (vm.installed.isEmpty()) {
            Panel {
                Text("Nothing installed yet.", style = MaterialTheme.typography.bodyMedium)
                AppButton("Install a system", { vm.open(Screen.InstallList) }, Modifier.fillMaxWidth(), R.drawable.ic_download)
            }
        } else {
            vm.installed.forEach { SystemCard(vm, it, showUsage = true) }
        }
    }
}

@Composable
fun DistroDetailScreen(vm: FirmwareViewModel, id: String) {
    val c = MaterialTheme.colorScheme
    val m = vm.installed.firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize()) {
        ScreenBar(m?.name ?: "System") { vm.back() }
        if (m == null) {
            Page(Modifier.weight(1f)) { Banner(Tone.Warn, "This system is no longer installed.") }
            return@Column
        }
        val ready = m.state == DistroState.EXTRACTED
        val idle = vm.desktopBusy == null && vm.opBusy == null && vm.installingId == null
        val hasDesktop = m.desktop != null
        val backups = vm.backups[m.id].orEmpty()

        Page(Modifier.weight(1f)) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DistroBadge(m.id, m.name, 56.dp)
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleLarge)
                        Text(m.version, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(m.state.label.sentence(), stateTone(m.state))
                    StatusChip(
                        if (hasDesktop) "Desktop installed" else if (DesktopInstaller.supports(m.id)) "No desktop" else "Shell only",
                        if (hasDesktop) Tone.Good else Tone.Neutral,
                    )
                    if (vm.sessionRunning) StatusChip("Running", Tone.Accent)
                }
            }

            Panel {
                when {
                    !ready -> Text("This system is not fully installed.", style = MaterialTheme.typography.bodyMedium)
                    hasDesktop -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppButton(
                            "Start desktop", { vm.bootDesktop(m.id) },
                            Modifier.weight(1f), R.drawable.ic_play, enabled = idle && vm.proot.available,
                        )
                        if (vm.sessionRunning) {
                            AppButton(
                                "Stop", { vm.stopDesktop() }, Modifier.weight(1f), R.drawable.ic_stop,
                                enabled = vm.desktopBusy == null, style = Btn.Outlined,
                            )
                        }
                    }
                    DesktopInstaller.supports(m.id) -> {
                        Text(
                            "Add a desktop to run graphical apps. Installs XFCE and a VNC server (about 1 GB).",
                            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                        )
                        AppButton(
                            "Install desktop", { vm.installDesktop(m.id) }, Modifier.fillMaxWidth(),
                            R.drawable.ic_monitor, enabled = idle && vm.proot.available,
                        )
                    }
                    else -> Text(
                        "A desktop recipe for this distribution has not been verified yet. The shell test and backups still work.",
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                    )
                }
                if (!vm.proot.available) Banner(Tone.Bad, "PRoot is unavailable: ${vm.proot.problem}")
            }

            val busy = vm.desktopBusy ?: vm.opBusy
            if (busy != null) {
                BusyPanel(
                    busy.sentence() + "...",
                    if (vm.desktopBusy != null) vm.desktopStatus else null,
                )
                if (vm.desktopBusy == null) {
                    Text("This step cannot show progress.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            } else if (vm.desktopStatus.isNotEmpty()) {
                Panel {
                    SectionTitle("Desktop log")
                    LogPanel(vm.desktopStatus.takeLast(40))
                }
            }
            vm.notice?.let { NoticeBanner(it) }
            vm.shellText?.let { out ->
                Panel {
                    SectionTitle("Shell test output")
                    LogPanel(out.lines().take(60))
                }
            }

            Panel {
                SectionTitle("Manage")
                ActionRow(
                    R.drawable.ic_terminal, "Run shell test", "Runs a few commands inside this system",
                    enabled = ready && vm.proot.available && !vm.shellRunning,
                ) { vm.runShellTest(m.id) }
                ActionRow(
                    R.drawable.ic_backup, "Back up",
                    if (backups.isEmpty()) "No backups yet" else "${backups.size} backup(s), newest ${formatBytes(backups.first().length())}",
                    enabled = ready && idle && !vm.sessionRunning,
                ) { vm.backupDistro(m.id) }
                ActionRow(
                    R.drawable.ic_restore, "Restore newest backup", backups.firstOrNull()?.name,
                    enabled = ready && idle && !vm.sessionRunning && backups.isNotEmpty(),
                ) { vm.open(Screen.ConfirmRestore(m.id)) }
                ActionRow(
                    R.drawable.ic_trash, "Remove", "Deletes its files and frees ${formatBytes(vm.usage[m.id] ?: 0L)}",
                    enabled = idle && !vm.sessionRunning, danger = true,
                ) { vm.open(Screen.ConfirmRemove(m.id)) }
            }

            Panel {
                SectionTitle("Details")
                InfoRow("Stored in", locText(m.location))
                InfoRow("Disk usage", formatBytes(vm.usage[m.id] ?: 0L))
                InfoRow("Root filesystem", m.rootfsPath, mono = true)
                InfoRow("Source", m.sourceUrl, mono = true)
                InfoRow("SHA-256", m.sha256, mono = true)
                m.message?.let { InfoRow("Last error", it) }
            }
        }
    }
}

@Composable
fun ConfirmRemoveDialog(vm: FirmwareViewModel, id: String) {
    val m = vm.installed.firstOrNull { it.id == id }
    AlertDialog(
        onDismissRequest = { vm.back() },
        title = { Text("Remove ${m?.name ?: id}?") },
        text = {
            Text(
                "This permanently deletes its files and frees ${formatBytes(vm.usage[id] ?: 0L)}. " +
                    "Backups you made are kept.",
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.remove(id) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { vm.back() }) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmRestoreDialog(vm: FirmwareViewModel, id: String) {
    val m = vm.installed.firstOrNull { it.id == id }
    val b = vm.backups[id]?.firstOrNull()
    AlertDialog(
        onDismissRequest = { vm.back() },
        title = { Text("Restore ${m?.name ?: id}?") },
        text = {
            Text(
                "This replaces the current files with ${b?.name ?: "the newest backup"}. Changes made after the " +
                    "backup are lost. The current files are kept until the restore succeeds.",
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.restoreDistro(id) }, enabled = b != null) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = { vm.back() }) { Text("Cancel") } },
    )
}

// ---- Settings, device info, maintenance -------------------------------------------------

@Composable
private fun ProbeRow(label: String, ok: Boolean) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Ico(
            if (ok) R.drawable.ic_check else R.drawable.ic_close, size = 18.dp,
            tint = if (ok) LocalExtraColors.current.success else c.error,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun probeRows(p: ProbeResult) {
    ProbeRow("Symbolic links", p.symlink)
    ProbeRow("Permission bits", p.chmod)
    ProbeRow("Hard links", p.hardlink)
    ProbeRow("Case-sensitive names", p.caseSensitive)
    Text(
        "Running programs from this storage: " + if (p.canExec) "yes" else "no (info only)",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    p.errors.forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun SettingsScreen(vm: FirmwareViewModel, onExit: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val d = vm.decision
    Page(Modifier.fillMaxSize()) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ico(R.drawable.ic_folder, tint = c.primary)
                Text("Storage", style = MaterialTheme.typography.titleMedium)
            }
            InfoRow("Data folder", vm.storage.dataRoot.path, mono = true)
            InfoRow("Type", if (vm.storage.isExternal) "App-specific shared storage" else "Internal storage (shared storage unavailable)")
            if (d == null) {
                Text(
                    "The storage test runs automatically on your first install. It checks whether Linux files " +
                        "(links, permissions) can live in shared storage.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                )
                vm.storage.decisionProblem?.let { Banner(Tone.Warn, "A saved result could not be read: $it") }
            } else {
                InfoRow("System files are stored in", locText(d.location))
                probeRows(d.probe)
            }
            AppButton("Run storage test", { vm.rerunProbe() }, Modifier.fillMaxWidth(), R.drawable.ic_restore, style = Btn.Tonal)
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ico(R.drawable.ic_terminal, tint = c.primary)
                Text("Runtime", style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    if (NativeRuntime.available) "Native library loaded" else "Native library missing",
                    if (NativeRuntime.available) Tone.Good else Tone.Bad,
                )
                StatusChip(
                    if (vm.proot.available) "PRoot ready" else "PRoot missing",
                    if (vm.proot.available) Tone.Good else Tone.Bad,
                )
            }
            if (!NativeRuntime.available) Banner(Tone.Bad, NativeRuntime.unavailableReason ?: "Unknown error")
            vm.proot.problem?.let { Banner(Tone.Bad, it) }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ico(R.drawable.ic_shield, tint = c.primary)
                Text("Privacy and permissions", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "LinuxBoot asks for one permission: Internet, used only to download root filesystems over HTTPS. " +
                    "The desktop's VNC server listens on this device only and uses a random password.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
            )
        }

        Panel {
            ActionRow(R.drawable.ic_cpu, "Device information", "Processor, memory and storage") { vm.open(Screen.Hardware) }
            ActionRow(R.drawable.ic_lifebuoy, "Maintenance", "Clear downloads and cache") { vm.open(Screen.Recovery) }
            ActionRow(R.drawable.ic_power, "Close LinuxBoot", "Stops the app", onClick = onExit)
        }
    }
}

@Composable
fun HardwareScreen(vm: FirmwareViewModel, info: SystemInfo) {
    Column(Modifier.fillMaxSize()) {
        ScreenBar("Device information") { vm.back() }
        Page(Modifier.weight(1f)) {
            Panel {
                InfoRow("Device", info.device)
                InfoRow("Android", "${info.androidRelease} (API ${info.sdk})")
                InfoRow("Processor architecture", info.arch)
                InfoRow("Supported ABIs", info.abis.joinToString(", "))
                InfoRow("CPU cores", info.cores.toString())
                InfoRow("Native ARM64 Linux", if (info.isArm64) "Supported" else "Not supported")
            }
            Panel {
                InfoRow("Memory", "${formatBytes(info.totalMemBytes)} total, ${formatBytes(info.availMemBytes)} available")
                InfoRow("Low-memory state", if (info.lowMemory) "Yes" else "No")
                InfoRow("Storage", "${formatBytes(info.storageFreeBytes)} free of ${formatBytes(info.storageTotalBytes)}")
                InfoRow("LinuxBoot folder free space", formatBytes(LinuxBootStorage.freeBytes(vm.storage.dataRoot)))
            }
        }
    }
}

@Composable
fun MaintenanceScreen(vm: FirmwareViewModel) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        ScreenBar("Maintenance") { vm.back() }
        Page(Modifier.weight(1f)) {
            Panel {
                SectionTitle("Downloads and cache")
                Text(
                    "${formatBytes(vm.cacheBytes)} used by partial downloads and temporary files.",
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                )
                AppButton(
                    "Clear downloads and cache", { vm.clearDownloads() }, Modifier.fillMaxWidth(),
                    R.drawable.ic_trash, enabled = vm.installingId == null, style = Btn.Tonal,
                )
                vm.notice?.let { NoticeBanner(it) }
            }
            Banner(Tone.Accent, "A recovery shell is not available yet.")
        }
    }
}
