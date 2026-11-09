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
import linuxboot.distro.Catalog
import linuxboot.distro.DistroMetadata
import linuxboot.distro.DistroStore
import linuxboot.distro.InstallStatus
import linuxboot.distro.Installer
import linuxboot.distro.Phase
import linuxboot.distro.ResolvedSource
import linuxboot.runtime.ProotRuntime
import linuxboot.storage.Fs
import linuxboot.storage.LinuxBootStorage
import linuxboot.storage.StorageDecision
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Boot : Screen
    data object InstallList : Screen
    data object BootManager : Screen
    data object Config : Screen
    data object Hardware : Screen
    data object Recovery : Screen
    data class InstallDetail(val id: String) : Screen
    data class DistroDetail(val id: String) : Screen
    data class ConfirmRemove(val id: String) : Screen
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
    private val store = DistroStore(storage)
    private val installer = Installer(storage, store)
    private var job: Job? = null

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

    init {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val list = withContext(Dispatchers.IO) { store.list() }
        val sizes = withContext(Dispatchers.IO) { list.associate { it.id to store.usage(it) } }
        val cache = withContext(Dispatchers.IO) {
            Fs.treeSize(storage.downloads) + Fs.treeSize(storage.cache)
        }
        installed = list
        usage = sizes
        cacheBytes = cache
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

    fun remove(id: String) {
        if (installingId == id) {
            notice = "CANNOT REMOVE WHILE INSTALLING"
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
