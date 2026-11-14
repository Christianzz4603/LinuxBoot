package linuxboot.system

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The four CPU types LinuxBoot runs on. A distribution only works if it was built for the same one:
 * guests run natively under PRoot and nothing is emulated.
 */
enum class CpuArch(
    val abi: String,
    val label: String,
    /** Directory name used by Alpine Linux. */
    val alpine: String,
    /** Architecture name used by the Linux Containers image server. */
    val lxc: String,
) {
    ARM64("arm64-v8a", "ARM 64-bit", "aarch64", "arm64"),
    ARM32("armeabi-v7a", "ARM 32-bit", "armv7", "armhf"),
    X86_64("x86_64", "x86 64-bit", "x86_64", "amd64"),
    X86("x86", "x86 32-bit", "x86", "i386");

    companion object {
        /**
         * The CPU type this app process runs as. Android extracts our native libraries (including
         * PRoot) into a folder named after it, which is the arch that matters for the guest.
         */
        fun detect(context: Context): CpuArch? {
            val byDir = when (File(context.applicationInfo.nativeLibraryDir).name) {
                "arm64" -> ARM64
                "arm" -> ARM32
                "x86_64" -> X86_64
                "x86" -> X86
                else -> null
            }
            return byDir ?: Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> values().firstOrNull { it.abi == abi } }
        }
    }
}
