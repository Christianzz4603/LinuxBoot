package linuxboot.runtime

import java.io.File
import java.io.IOException

data class ExtractReport(
    val dirs: Long,
    val files: Long,
    val symlinks: Long,
    val hardlinks: Long,
    val skipped: Long,
)

/** Kotlin side of the Rust runtime. Reports a missing library instead of crashing. */
object NativeRuntime {
    private val loadError: String? = try {
        System.loadLibrary("linuxboot_runtime")
        null
    } catch (e: UnsatisfiedLinkError) {
        e.message ?: "library not found"
    }

    val available: Boolean get() = loadError == null
    val unavailableReason: String? get() = loadError

    // Private on purpose: internal members get mangled names that JNI cannot find.
    @JvmStatic private external fun nativeVerifySha256(path: String, expected: String): String
    @JvmStatic private external fun nativeExtract(archive: String, root: String): String

    fun verifySha256(file: File, expected: String): Result<Unit> {
        if (!available) return Result.failure(IllegalStateException("Native runtime unavailable: $loadError"))
        val r = nativeVerifySha256(file.path, expected)
        return if (r == "OK") Result.success(Unit) else Result.failure(IOException(r.removePrefix("ERR:")))
    }

    fun extract(archive: File, root: File): Result<ExtractReport> {
        if (!available) return Result.failure(IllegalStateException("Native runtime unavailable: $loadError"))
        val r = nativeExtract(archive.path, root.path)
        if (!r.startsWith("OK:")) return Result.failure(IOException(r.removePrefix("ERR:")))
        val n = r.removePrefix("OK:").split(",").map { it.toLong() }
        return Result.success(ExtractReport(n[0], n[1], n[2], n[3], n[4]))
    }
}
