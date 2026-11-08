package linuxboot.distro

import java.io.IOException

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
    val resolve: suspend () -> ResolvedSource,
)

/**
 * Distributions LinuxBoot can install. An entry exists only once its ARM64 source and
 * checksum location have been verified. Sources are resolved at install time so the
 * app never pins a stale version.
 */
object Catalog {
    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "alpine",
            name = "Alpine Linux",
            note = "Official aarch64 minirootfs. musl libc, apk package manager.",
            resolve = { resolveAlpine() },
        ),
    )

    fun find(id: String): CatalogEntry? = entries.firstOrNull { it.id == id }

    private const val ALPINE_BASE =
        "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/"

    /**
     * Reads the current minirootfs file name from Alpine's release manifest and its
     * SHA-256 from the .sha256 file published next to it. Both come over HTTPS from
     * the same origin: this detects corruption and truncation, but is not a separate
     * trust root. The .asc GPG signature is not verified yet.
     */
    private suspend fun resolveAlpine(): ResolvedSource {
        val manifest = Http.getText(ALPINE_BASE + "latest-releases.yaml")
        val file = Regex("alpine-minirootfs-[0-9][0-9.]*-aarch64[.]tar[.]gz").find(manifest)?.value
            ?: throw IOException("minirootfs not listed in Alpine's latest-releases.yaml")
        val sumText = Http.getText(ALPINE_BASE + file + ".sha256").trim()
        val sha = sumText.substringBefore(' ').lowercase()
        if (!Regex("[0-9a-f]{64}").matches(sha)) throw IOException("Malformed checksum file for $file")
        val version = Regex("alpine-minirootfs-(.+)-aarch64").find(file)!!.groupValues[1]
        val size = Http.contentLength(ALPINE_BASE + file)
        if (size <= 0) throw IOException("Server did not report the size of $file")
        return ResolvedSource(version, file, ALPINE_BASE + file, sha, size, size * 4)
    }
}
