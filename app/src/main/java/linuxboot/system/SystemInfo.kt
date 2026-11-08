package linuxboot.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File
import java.util.Locale

/** Snapshot of real device values. Nothing here is hard-coded. */
data class SystemInfo(
    val abis: List<String>,
    val arch: String,
    val isArm64: Boolean,
    val totalMemBytes: Long,
    val availMemBytes: Long,
    val lowMemory: Boolean,
    val storageTotalBytes: Long,
    val storageFreeBytes: Long,
    val androidRelease: String,
    val sdk: Int,
    val device: String,
    val cores: Int,
    val filesPath: String,
    val runtimeInstalled: Boolean,
) {
    companion object {
        fun detect(context: Context): SystemInfo {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val files = context.filesDir
            val sf = StatFs(files.absolutePath)
            return SystemInfo(
                abis = Build.SUPPORTED_ABIS.toList(),
                arch = System.getProperty("os.arch") ?: "unknown",
                isArm64 = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a"),
                totalMemBytes = mi.totalMem,
                availMemBytes = mi.availMem,
                lowMemory = mi.lowMemory,
                storageTotalBytes = sf.totalBytes,
                storageFreeBytes = sf.availableBytes,
                androidRelease = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                cores = Runtime.getRuntime().availableProcessors(),
                filesPath = files.absolutePath,
                runtimeInstalled = File(files, "runtime/proot").exists(),
            )
        }
    }
}

fun formatBytes(bytes: Long): String {
    val gib = bytes / 1073741824.0
    return if (gib >= 1.0) String.format(Locale.US, "%.2f GiB", gib)
    else String.format(Locale.US, "%.0f MiB", bytes / 1048576.0)
}
