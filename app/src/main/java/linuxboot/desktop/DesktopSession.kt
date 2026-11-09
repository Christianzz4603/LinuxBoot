package linuxboot.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import linuxboot.runtime.ProotRuntime
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs Xvnc and XFCE inside a distribution under PRoot. The VNC server listens on
 * loopback only and requires a password, because any app on the device can reach 127.0.0.1.
 */
class DesktopSession(private val proot: ProotRuntime) {
    @Volatile
    private var process: Process? = null

    val running: Boolean get() = process?.isAlive == true

    private fun script(width: Int, height: Int): String = listOf(
        "export HOME=/root DISPLAY=:1 XDG_RUNTIME_DIR=/tmp/runtime-root LANG=C.UTF-8",
        "mkdir -p /tmp/runtime-root /tmp/.X11-unix",
        "chmod 700 /tmp/runtime-root",
        "chmod 1777 /tmp/.X11-unix",
        "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1",
        "Xvnc :1 -geometry ${width}x$height -depth 24 -rfbport $PORT -rfbauth /root/.vnc/passwd " +
            "-localhost -SecurityTypes VncAuth -AlwaysShared -desktop LinuxBoot &",
        "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do [ -e /tmp/.X11-unix/X1 ] && break; sleep 1; done",
        "exec dbus-run-session -- startxfce4",
    ).joinToString(System.lineSeparator())

    private fun portOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 500) }
        true
    } catch (e: IOException) {
        false
    }

    /** Starts the desktop and waits until the VNC port accepts connections. */
    suspend fun start(rootfs: File, width: Int, height: Int, onLine: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                stop()
                val p = proot.spawn(rootfs, script(width, height))
                process = p
                val tail = ArrayDeque<String>()
                Thread({
                    p.inputStream.bufferedReader().forEachLine { l ->
                        onLine(l)
                        synchronized(tail) {
                            tail.addLast(l)
                            if (tail.size > 30) tail.removeFirst()
                        }
                    }
                }, "desktop-log").apply { isDaemon = true }.start()

                val deadline = System.currentTimeMillis() + 120_000
                while (System.currentTimeMillis() < deadline) {
                    if (!p.isAlive) {
                        val last = synchronized(tail) { tail.joinToString(System.lineSeparator()) }
                        throw IOException("Desktop process exited (code ${p.exitValue()})" + System.lineSeparator() + last)
                    }
                    if (portOpen()) return@runCatching
                    delay(1000)
                }
                throw IOException("Timed out waiting for the VNC server on 127.0.0.1:$PORT")
            }.onFailure { stop() }
        }

    fun stop() {
        val p = process ?: return
        process = null
        p.destroy()
        if (p.isAlive) p.destroyForcibly()
    }

    companion object {
        const val PORT = 5901
    }
}
