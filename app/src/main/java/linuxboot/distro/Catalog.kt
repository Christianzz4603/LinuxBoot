package linuxboot.distro

import linuxboot.system.CpuArch
import java.io.IOException
import java.net.URLDecoder

/** A concrete, checksummed download for one distribution. */
data class ResolvedSource(
    val version: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Heuristic upper estimate of the extracted size; used only for the free-space check. */
    val estimatedInstalledBytes: Long,
)

class CatalogEntry(
    val id: String,
    val name: String,
    val note: String,
    /** CPU types this distribution publishes a root filesystem for (checked against the sources). */
    val archs: Set<CpuArch>,
    val resolve: suspend (CpuArch) -> ResolvedSource,
) {
    fun supports(arch: CpuArch?): Boolean = arch != null && arch in archs
}

/**
 * Distributions LinuxBoot can install. An entry exists only once its source and checksum location were
 * verified for the CPU types listed. Sources are resolved at install time so the app never pins a
 * stale version, and always for the CPU the app runs on: nothing is emulated.
 */
object Catalog {
    private val everyCpu = CpuArch.values().toSet()
    private val armAndAmd64 = setOf(CpuArch.ARM64, CpuArch.ARM32, CpuArch.X86_64)
    private val arm64AndAmd64 = setOf(CpuArch.ARM64, CpuArch.X86_64)

    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "alpine",
            name = "Alpine Linux",
            note = "Official minirootfs for every CPU type. musl libc, apk. Desktop install supported.",
            archs = everyCpu,
            resolve = { arch -> resolveAlpine(arch) },
        ),
        lxc("debian", "Debian 13 (Trixie)", "debian/trixie", "glibc, apt. Desktop install supported.", armAndAmd64),
        lxc("ubuntu", "Ubuntu 24.04 LTS", "ubuntu/noble", "glibc, apt. Desktop install supported.", armAndAmd64),
        lxc("arch", "Arch Linux", "archlinux/current", "pacman (Arch Linux ARM on ARM). Rootfs and shell only: no verified desktop recipe yet.", arm64AndAmd64),
        lxc("fedora", "Fedora 44", "fedora/44", "dnf. Rootfs and shell only: no verified desktop recipe yet.", arm64AndAmd64),
        lxc("void", "Void Linux", "voidlinux/current", "xbps. Rootfs and shell only: no verified desktop recipe yet.", arm64AndAmd64),
    )

    fun find(id: String): CatalogEntry? = entries.firstOrNull { it.id == id }

    private fun lxc(id: String, name: String, path: String, note: String, archs: Set<CpuArch>) = CatalogEntry(
        id = id,
        name = name,
        note = "$note Image built by the Linux Containers project, not by the distribution itself.",
        archs = archs,
        resolve = { arch -> resolveLxc(id, path, arch) },
    )

    /**
     * Reads the current minirootfs file name from Alpine's release manifest and its SHA-256 from the
     * .sha256 file published next to it. Both come over HTTPS from the same origin: this detects
     * corruption and truncation, but is not a separate trust root. The .asc GPG signature is not
     * verified yet.
     */
    private suspend fun resolveAlpine(arch: CpuArch): ResolvedSource {
        val base = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/${arch.alpine}/"
        val manifest = Http.getText(base + "latest-releases.yaml")
        val file = Regex("alpine-minirootfs-[0-9][0-9.]*-" + Regex.escape(arch.alpine) + "[.]tar[.]gz")
            .find(manifest)?.value
            ?: throw IOException("minirootfs for ${arch.alpine} is not listed in Alpine's latest-releases.yaml")
        val sumText = Http.getText(base + file + ".sha256").trim()
        val sha = sumText.substringBefore(' ').lowercase()
        if (!Regex("[0-9a-f]{64}").matches(sha)) throw IOException("Malformed checksum file for $file")
        val version = file.removePrefix("alpine-minirootfs-").removeSuffix("-${arch.alpine}.tar.gz")
        val size = Http.contentLength(base + file)
        if (size <= 0) throw IOException("Server did not report the size of $file")
        return ResolvedSource(version, file, base + file, sha, size, size * 4)
    }

    private const val LXC_BASE = "https://images.linuxcontainers.org/images/"

    /**
     * Finds the newest complete build of a Linux Containers image for [arch] and reads the SHA-256 of
     * its rootfs.tar.xz from the SHA256SUMS file next to it. Same-origin checksum: it detects
     * corruption, not a compromised server. SHA256SUMS.asc is not verified yet. Tries the three
     * newest builds in case the latest is incomplete.
     */
    private suspend fun resolveLxc(id: String, path: String, arch: CpuArch): ResolvedSource {
        val dirUrl = "$LXC_BASE$path/${arch.lxc}/default/"
        val listing = try {
            Http.getText(dirUrl)
        } catch (e: IOException) {
            throw IOException("No ${arch.label} build is published for this system (${e.message})")
        }
        val builds = Regex("href=\"([0-9]{8}_[0-9A-Fa-f%]+)/\"")
            .findAll(listing).map { it.groupValues[1] }.toList().sortedDescending().take(3)
        if (builds.isEmpty()) throw IOException("No ${arch.label} builds are listed at $dirUrl")

        var lastError: IOException? = null
        for (b in builds) {
            try {
                val base = "$dirUrl$b/"
                val sums = Http.getText(base + "SHA256SUMS")
                val sha = sums.lineSequence().map { it.trim() }
                    .firstOrNull { it.endsWith("rootfs.tar.xz") }
                    ?.substringBefore(' ')?.lowercase()
                    ?: throw IOException("rootfs.tar.xz is not listed in SHA256SUMS")
                if (!Regex("[0-9a-f]{64}").matches(sha)) throw IOException("Malformed SHA256SUMS")
                val size = Http.contentLength(base + "rootfs.tar.xz")
                if (size <= 0) throw IOException("Server did not report the rootfs size")
                val stamp = URLDecoder.decode(b, "UTF-8")
                val fileName = id + "-" + arch.lxc + "-" + stamp.replace(":", "").replace("_", "-") + ".tar.xz"
                return ResolvedSource(stamp, fileName, base + "rootfs.tar.xz", sha, size, size * 5)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw IOException("No complete build found: ${lastError?.message}")
    }
}
