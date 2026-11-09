package linuxboot.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/**
 * Runs commands inside an extracted rootfs with PRoot (no root, no emulation).
 * PRoot and its loader are executed from nativeLibraryDir, the one place Android
 * lets an app execute its own files. Process launch lives in Kotlin for now; moving
 * it into the Rust runtime is future work.
 */
class ProotRuntime(context: Context) {
    private val libDir = File(context.applicationInfo.nativeLibraryDir)
    private val proot = File(libDir, "libproot.so")
    private val loader = File(libDir, "libproot-loader.so")
    private val tmpDir = File(context.cacheDir, "proot-tmp")

    /** Null when PRoot is ready, otherwise the reason it is not. */
    val problem: String?
        get() = when {
            !proot.exists() -> "libproot.so not found in ${libDir.path} (not packaged into this APK)"
            !loader.exists() -> "libproot-loader.so not found in ${libDir.path}"
            !proot.canExecute() -> "libproot.so is not executable"
            else -> null
        }

    val available: Boolean get() = problem == null

    private fun builder(rootfs: File, script: String): ProcessBuilder {
        check(available) { problem ?: "PRoot unavailable" }
        tmpDir.mkdirs()
        val cmd = listOf(
            proot.path, "--link2symlink", "-0", "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root", "/bin/sh", "-c", script,
        )
        val pb = ProcessBuilder(cmd)
        pb.environment().apply {
            clear()
            put("PROOT_TMP_DIR", tmpDir.path)
            put("PROOT_LOADER", loader.path)
            put("HOME", "/root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
        }
        return pb
    }

    /** Starts a long-running process (stdout and stderr merged). The caller owns it. */
    fun spawn(rootfs: File, script: String): Process {
        val p = builder(rootfs, script).redirectErrorStream(true).start()
        p.outputStream.close()
        return p
    }

    suspend fun runShell(rootfs: File, script: String, timeoutSeconds: Long = 30): ShellResult =
        withContext(Dispatchers.IO) {
            val p = builder(rootfs, script).start()
            p.outputStream.close()
            coroutineScope {
                val out = async { p.inputStream.bufferedReader().readText().take(MAX_OUTPUT) }
                val err = async { p.errorStream.bufferedReader().readText().take(MAX_OUTPUT) }
                val finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) p.destroyForcibly()
                ShellResult(if (finished) p.exitValue() else -1, out.await(), err.await(), !finished)
            }
        }

    /** Runs a script and reports each output line as it arrives. Returns the exit code, or -1 on timeout. */
    suspend fun runStreaming(rootfs: File, script: String, timeoutMinutes: Long, onLine: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val p = spawn(rootfs, script)
            try {
                coroutineScope {
                    val reader = async { p.inputStream.bufferedReader().forEachLine(onLine) }
                    val finished = p.waitFor(timeoutMinutes, TimeUnit.MINUTES)
                    if (!finished) p.destroyForcibly()
                    reader.await()
                    if (finished) p.exitValue() else -1
                }
            } finally {
                if (p.isAlive) p.destroyForcibly()
            }
        }

    private companion object {
        const val MAX_OUTPUT = 65536
    }
}
