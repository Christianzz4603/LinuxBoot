package linuxboot.distro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTPS-only helpers. Plain http:// is refused. */
object Http {
    fun open(url: String, method: String = "GET", rangeFrom: Long = 0L): HttpURLConnection {
        require(url.startsWith("https://")) { "Refusing non-HTTPS URL" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "LinuxBoot/1.0")
        if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
        return c
    }

    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun contentLength(url: String): Long = withContext(Dispatchers.IO) {
        val c = open(url, method = "HEAD")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
            c.contentLengthLong
        } finally {
            c.disconnect()
        }
    }
}

data class DownloadProgress(val downloaded: Long, val total: Long, val bytesPerSec: Long)

object Downloader {
    /**
     * Downloads [url] to [dest] through a .part file and resumes it with an HTTP Range
     * request if one already exists. Throws if the transfer ends short; the .part file
     * is kept so the next attempt resumes. Cancelling the coroutine also keeps it.
     */
    suspend fun download(
        url: String,
        dest: File,
        expectedSize: Long,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val part = File(dest.parentFile, dest.name + ".part")
            var restarted = false
            while (true) {
                val have = if (part.exists()) part.length() else 0L
                val c = Http.open(url, rangeFrom = have)
                try {
                    val code = c.responseCode
                    if (code == 416 && !restarted) {
                        part.delete()
                        restarted = true
                        continue
                    }
                    if (code != 200 && code != 206) throw IOException("HTTP $code")
                    val append = code == 206
                    val remaining = c.contentLengthLong
                    val total = when {
                        remaining <= 0 -> expectedSize
                        append -> have + remaining
                        else -> remaining
                    }
                    var done = if (append) have else 0L

                    FileOutputStream(part, append).use { out ->
                        c.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var windowStart = System.nanoTime()
                            var windowBytes = 0L
                            var speed = 0L
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                windowBytes += n
                                val now = System.nanoTime()
                                if (now - windowStart >= 500_000_000L) {
                                    speed = windowBytes * 1_000_000_000L / (now - windowStart)
                                    windowStart = now
                                    windowBytes = 0L
                                    onProgress(DownloadProgress(done, total, speed))
                                }
                            }
                            onProgress(DownloadProgress(done, total, speed))
                        }
                    }

                    if (total > 0 && done != total) {
                        throw IOException("Download incomplete: $done of $total bytes (will resume)")
                    }
                    if (dest.exists()) dest.delete()
                    if (!part.renameTo(dest)) throw IOException("Could not finalize download")
                    return@withContext
                } finally {
                    c.disconnect()
                }
            }
        }
    }
}
