package linuxboot.firmware

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import linuxboot.desktop.DesktopSession
import linuxboot.desktop.RfbClient
import linuxboot.desktop.ViewerController
import linuxboot.distro.Catalog
import linuxboot.distro.DesktopInstaller
import linuxboot.distro.DistroMetadata
import linuxboot.distro.DistroStore
import linuxboot.distro.InstallStatus
import linuxboot.distro.Installer
import linuxboot.distro.Phase
import linuxboot.distro.ResolvedSource
import linuxboot.runtime.NativeRuntime
import linuxboot.runtime.ProotRuntime
import linuxboot.storage.Fs
import linuxboot.storage.LinuxBootStorage
import linuxboot.storage.StorageDecision
import linuxboot.system.SystemInfo
import linuxboot.system.formatBytes
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Rough floor for Xvnc + XFCE; below this Android is likely to kill the session. A heuristic, not a measurement. */
private const val MIN_FREE_RAM = 700L * 1024 * 1024

sealed interface Screen {
    data object Home : Screen
    data object Boot : Screen
    data object InstallList : Screen
    data object BootManager : Screen
    data object Config : Screen
    data object Hardware : Screen
    data object Recovery : Screen
    data object Viewer : Screen
    data class InstallDetail(val id: String) : Screen
    data class DistroDetail(val id: String) : Screen
    data class ConfirmRemove(val id: String) : Screen
    data class ConfirmRestore(val id: String) : Screen
}

sealed interface SourceState {
    data object Idle : SourceState
    data object Loading : SourceState
    data class Ready(val src: ResolvedSource) : SourceState
    data class Error(val message: String) : SourceState
}

class FirmwareViewModel(app: Application) : AndroidViewModel(app) {
    val storage = LinuxBootStorage(app)
    val proot = ProotRuntime(app)
    val session = DesktopSession(proot)
    private val store = DistroStore(storage)
    private val installer = Installer(storage, store)
    private var job: Job? = null
    private var desktopJob: Job? = null
    private var client: RfbClient? = null
    private var viewerId: String? = null

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var installed by mutableStateOf<List<DistroMetadata>>(emptyList())
        private set
    var usage by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    var cacheBytes by mutableStateOf(0L)
        private set
    var source by mutableStateOf<SourceState>(SourceState.Idle)
        private set
    var status by mutableStateOf<InstallStatus?>(null)
        private set
    var installingId by mutableStateOf<String?>(null)
        private set
    var decision by mutableStateOf<StorageDecision?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var shellText by mutableStateOf<String?>(null)
        private set
    var shellRunning by mutableStateOf(false)
        private set
    var viewer by mutableStateOf<ViewerController?>(null)
        private set
    var desktopStatus by mutableStateOf<List<String>>(emptyList())
        private set
    var desktopBusy by mutableStateOf<String?>(null)
        private set
    var sessionRunning by mutableStateOf(false)
        private set
    var backups by mutableStateOf<Map<String, List<File>>>(emptyMap())
        private set
    var opBusy by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val list = withContext(Dispatchers.IO) { store.list() }
        val sizes = withContext(Dispatchers.IO) { list.associate { it.id to store.usage(it) } }
        val cache = withContext(Dispatchers.IO) {
            Fs.treeSize(storage.downloads) + Fs.treeSize(storage.cache)
        }
        val bk = withContext(Dispatchers.IO) {
            list.associate { m ->
                m.id to (storage.backups.listFiles { f -> f.name.startsWith(m.id + "-") && f.name.endsWith(".tar.xz") }
                    ?.sortedByDescending { it.name } ?: emptyList())
            }
        }
        installed = list
        usage = sizes
        cacheBytes = cache
        backups = bk
    }

    fun open(s: Screen) {
        notice = null
        screen = s
        when (s) {
            is Screen.InstallDetail -> {
                if (installingId != s.id) status = null
                loadDecision()
                resolveSource(s.id)
            }
            is Screen.DistroDetail -> shellText = null
            Screen.Config -> loadDecision()
            Screen.Boot, Screen.BootManager, Screen.Recovery, Screen.InstallList ->
                viewModelScope.launch { refresh() }
            else -> Unit
        }
    }

    fun back() {
        screen = when (val s = screen) {
            Screen.Home -> Screen.Home
            is Screen.InstallDetail -> Screen.InstallList
            is Screen.DistroDetail -> Screen.BootManager
            is Screen.ConfirmRemove -> Screen.DistroDetail(s.id)
            is Screen.ConfirmRestore -> Screen.DistroDetail(s.id)
            else -> Screen.Home
        }
    }

    private fun loadDecision() {
        viewModelScope.launch {
            decision = withContext(Dispatchers.IO) { storage.loadDecision() }
        }
    }

    private fun resolveSource(id: String) {
        val entry = Catalog.find(id) ?: return
        source = SourceState.Loading
        viewModelScope.launch {
            source = try {
                SourceState.Ready(entry.resolve())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SourceState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun retryResolve(id: String) = resolveSource(id)

    fun startInstall(id: String) {
        if (job?.isActive == true) return
        val entry = Catalog.find(id) ?: return
        installingId = id
        status = null
        job = viewModelScope.launch {
            try {
                installer.install(entry) { status = it }
            } finally {
                installingId = null
                withContext(NonCancellable) { refresh() }
            }
        }
    }

    /** A partial download is kept so the next install resumes it. Extraction finishes its current step first. */
    fun cancelInstall() {
        job?.cancel()
        status = InstallStatus(Phase.CANCELLED, "Cancelled. Install again to resume the download.")
    }

    /** Runs a few real commands inside the distribution under PRoot and shows the raw output. */
    fun runShellTest(id: String) {
        val m = installed.firstOrNull { it.id == id } ?: return
        if (shellRunning) return
        shellRunning = true
        shellText = "RUNNING..."
        viewModelScope.launch {
            shellText = try {
                val r = proot.runShell(File(m.rootfsPath), "uname -a; echo; cat /etc/os-release; echo; id")
                buildString {
                    appendLine("EXIT CODE: ${r.exitCode}" + if (r.timedOut) " (TIMED OUT)" else "")
                    if (r.stdout.isNotBlank()) {
                        appendLine("--- STDOUT ---")
                        appendLine(r.stdout.trimEnd())
                    }
                    if (r.stderr.isNotBlank()) {
                        appendLine("--- STDERR ---")
                        appendLine(r.stderr.trimEnd())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "FAILED TO START: ${e.message}"
            }
            shellRunning = false
        }
    }

    // ---- desktop --------------------------------------------------------------------------

    private fun log(line: String) {
        desktopStatus = (desktopStatus + line).takeLast(200)
    }

    /** Installs XFCE and a VNC server inside the distribution, streaming the package manager's output. */
    fun installDesktop(id: String) {
        val m = installed.firstOrNull { it.id == id } ?: return
        if (desktopBusy != null || installingId != null) return
        val password = DesktopInstaller.randomPassword()
        val script = DesktopInstaller.script(m.id, password)
        if (script == null) {
            notice = "NO VERIFIED DESKTOP RECIPE FOR ${m.name}"
            return
        }
        desktopBusy = "INSTALLING DESKTOP"
        desktopStatus = emptyList()
        desktopJob = viewModelScope.launch {
            try {
                val free = LinuxBootStorage.freeBytes(File(m.rootfsPath))
                if (free < DesktopInstaller.REQUIRED_FREE) {
                    log("NOT ENOUGH SPACE: need ${formatBytes(DesktopInstaller.REQUIRED_FREE)}, have ${formatBytes(free)}")
                    return@launch
                }
                val code = proot.runStreaming(File(m.rootfsPath), script, 40) { line -> log(line) }
                val ok = code == 0 && desktopStatus.any { it.contains("DESKTOP_INSTALL_OK") }
                if (ok) {
                    withContext(Dispatchers.IO) { store.save(m.copy(desktop = "xfce", vncPassword = password)) }
                    log("DESKTOP INSTALLED")
                } else {
                    log("DESKTOP INSTALL FAILED (exit code $code)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("FAILED: ${e.message}")
            } finally {
                desktopBusy = null
                withContext(NonCancellable) { refresh() }
            }
        }
    }

    private fun autoGeometry(): Pair<Int, Int> {
        val dm = getApplication<Application>().resources.displayMetrics
        val long = max(dm.widthPixels, dm.heightPixels)
        val short = min(dm.widthPixels, dm.heightPixels)
        val w = min(1280, long) and 1.inv()
        val h = (w * short / long) and 1.inv()
        return w to h
    }

    /** Starts the desktop (if needed), connects the embedded viewer and opens it. */
    fun bootDesktop(id: String, width: Int = 0, height: Int = 0) {
        val m = installed.firstOrNull { it.id == id } ?: return
        val password = m.vncPassword
        if (m.desktop == null || password == null) {
            notice = "NO DESKTOP INSTALLED"
            return
        }
        if (desktopBusy != null) return
        val mem = SystemInfo.detect(getApplication())
        if (mem.lowMemory || mem.availMemBytes < MIN_FREE_RAM) {
            val msg = "NOT ENOUGH FREE RAM TO START A DESKTOP: ${formatBytes(mem.availMemBytes)} free, " +
                "about ${formatBytes(MIN_FREE_RAM)} needed. Close other apps and try again."
            notice = msg
            desktopStatus = listOf(msg)
            return
        }
        val (w, h) = if (width > 0 && height > 0) width to height else autoGeometry()
        desktopBusy = "STARTING DESKTOP"
        desktopStatus = emptyList()
        desktopJob = viewModelScope.launch {
            try {
                if (!session.running) {
                    session.start(File(m.rootfsPath), w, h) { line -> log(line) }.getOrThrow()
                    sessionRunning = true
                }
                val c = RfbClient("127.0.0.1", DesktopSession.PORT, password)
                withContext(Dispatchers.IO) { c.connect() }
                val prefs = getApplication<Application>().getSharedPreferences("viewer", android.content.Context.MODE_PRIVATE)
                val learned = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
                prefs.getStringSet("text_pointers", emptySet())?.forEach { s -> s.toIntOrNull()?.let { learned.add(it) } }
                val ctl = ViewerController(c, learned) { set ->
                    prefs.edit().putStringSet("text_pointers", set.map { it.toString() }.toSet()).apply()
                }
                c.start(ctl)
                client = c
                viewer = ctl
                viewerId = id
                screen = Screen.Viewer
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("BOOT FAILED: ${e.message}")
                session.stop()
                sessionRunning = false
            } finally {
                desktopBusy = null
            }
        }
    }

    fun exitViewer(stop: Boolean) {
        client?.close()
        client = null
        viewer = null
        if (stop) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { session.stop() }
                sessionRunning = false
            }
        }
        screen = viewerId?.let { Screen.DistroDetail(it) } ?: Screen.BootManager
    }

    fun reconnect() {
        val id = viewerId ?: return
        exitViewer(false)
        bootDesktop(id)
    }

    /** Resolution changes restart the desktop session with the new framebuffer size. */
    fun restartDesktop(width: Int, height: Int) {
        val id = viewerId ?: return
        client?.close()
        client = null
        viewer = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { session.stop() }
            sessionRunning = false
            bootDesktop(id, width, height)
        }
    }

    fun stopDesktop() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { session.stop() }
            sessionRunning = false
        }
    }

    /** Archives the rootfs into LinuxBoot/backups. A blocking native call: no progress and no cancel. */
    fun backupDistro(id: String) {
        val m = installed.firstOrNull { it.id == id } ?: return
        if (opBusy != null || desktopBusy != null || installingId != null || sessionRunning) {
            notice = "STOP THE DESKTOP AND FINISH OTHER OPERATIONS FIRST"
            return
        }
        opBusy = "BACKING UP"
        notice = null
        viewModelScope.launch {
            try {
                storage.ensureLayout()
                val need = (usage[id] ?: 0L) / 2 + 32L * 1024 * 1024
                val free = LinuxBootStorage.freeBytes(storage.backups)
                if (free < need) {
                    notice = "NOT ENOUGH SPACE FOR A BACKUP (estimate): need about ${formatBytes(need)}, have ${formatBytes(free)}"
                    return@launch
                }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val out = File(storage.backups, "$id-$stamp.tar.xz")
                val r = withContext(Dispatchers.IO) { NativeRuntime.backup(File(m.rootfsPath), out) }
                notice = r.fold(
                    { "BACKUP OK: ${out.name} (${formatBytes(out.length())}), ${it.files} files, ${it.symlinks} symlinks, ${it.skipped} special files skipped" },
                    { "BACKUP FAILED: ${it.message}" },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = "BACKUP FAILED: ${e.message}"
            } finally {
                opBusy = null
                withContext(NonCancellable) { refresh() }
            }
        }
    }

    /** Restores the newest backup into a staging directory and swaps it in only if extraction succeeds. */
    fun restoreDistro(id: String) {
        val m = installed.firstOrNull { it.id == id } ?: return
        screen = Screen.DistroDetail(id)
        val backup = backups[id]?.firstOrNull()
        if (backup == null) {
            notice = "NO BACKUP FOUND"
            return
        }
        if (opBusy != null || desktopBusy != null || installingId != null || sessionRunning) {
            notice = "STOP THE DESKTOP AND FINISH OTHER OPERATIONS FIRST"
            return
        }
        opBusy = "RESTORING"
        notice = null
        viewModelScope.launch {
            try {
                val rootfs = File(m.rootfsPath)
                val base = rootfs.parentFile ?: throw IOException("Bad rootfs path")
                check(storage.owns(base)) { "Refusing to touch ${base.path}" }
                val need = backup.length() * 5
                val free = LinuxBootStorage.freeBytes(base)
                if (free < need) {
                    notice = "NOT ENOUGH SPACE TO RESTORE (estimate): need about ${formatBytes(need)}, have ${formatBytes(free)}"
                    return@launch
                }
                notice = withContext(Dispatchers.IO) {
                    val staging = File(base, "rootfs.restoring")
                    val old = File(base, "rootfs.old")
                    Fs.deleteTree(staging)
                    Fs.deleteTree(old)
                    staging.mkdirs()
                    try {
                        NativeRuntime.extract(backup, staging).getOrThrow()
                        if (!rootfs.renameTo(old)) throw IOException("Could not set the current rootfs aside")
                        if (!staging.renameTo(rootfs)) {
                            old.renameTo(rootfs)
                            throw IOException("Could not move the restored rootfs into place")
                        }
                        Fs.deleteTree(old)
                        "RESTORED FROM ${backup.name}"
                    } catch (e: Exception) {
                        Fs.deleteTree(staging)
                        "RESTORE FAILED (current rootfs kept): ${e.message}"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = "RESTORE FAILED: ${e.message}"
            } finally {
                opBusy = null
                withContext(NonCancellable) { refresh() }
            }
        }
    }

    override fun onCleared() {
        client?.close()
        session.stop()
    }

    // ---- storage --------------------------------------------------------------------------

    fun remove(id: String) {
        if (installingId == id || sessionRunning || opBusy != null) {
            notice = "STOP THE DESKTOP / INSTALL FIRST"
            return
        }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { store.remove(id) }
            notice = r.exceptionOrNull()?.let { "REMOVE FAILED: ${it.message}" } ?: "REMOVED"
            refresh()
            screen = Screen.BootManager
        }
    }

    fun rerunProbe() {
        viewModelScope.launch {
            decision = withContext(Dispatchers.IO) { storage.decide(force = true) }
        }
    }

    fun clearDownloads() {
        if (installingId != null) {
            notice = "CANNOT CLEAR WHILE AN INSTALL IS RUNNING"
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                listOf(storage.downloads, storage.cache).forEach { d ->
                    d.listFiles()?.forEach { Fs.deleteTree(it) }
                }
            }
            notice = "DOWNLOADS AND CACHE CLEARED"
            refresh()
        }
    }
}
