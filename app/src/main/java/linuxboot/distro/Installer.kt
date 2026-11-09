package linuxboot.distro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import linuxboot.runtime.NativeRuntime
import linuxboot.storage.Fs
import linuxboot.storage.LinuxBootStorage
import linuxboot.storage.Placement
import linuxboot.system.formatBytes
import java.io.File
import java.io.IOException
import java.nio.file.Files

enum class Phase(val label: String) {
    PREPARING("PREPARING"),
    RESOLVING("RESOLVING SOURCE"),
    DOWNLOADING("DOWNLOADING"),
    VERIFYING("VERIFYING SHA-256"),
    EXTRACTING("EXTRACTING"),
    CONFIGURING("CONFIGURING"),
    DONE("DONE"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED"),
}

data class InstallStatus(
    val phase: Phase,
    val detail: String = "",
    val progress: DownloadProgress? = null,
)

/**
 * Download, verify, extract and configure one distribution.
 *
 * Not done yet: creating a Linux user, and installing packages or a desktop inside
 * the rootfs under PRoot. The distribution is registered as INSTALLED (rootfs ready).
 */
class Installer(private val storage: LinuxBootStorage, private val store: DistroStore) {

    suspend fun install(entry: CatalogEntry, onStatus: (InstallStatus) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            var logFile: File? = null
            var rootfsToClean: File? = null

            fun say(s: InstallStatus) {
                onStatus(s)
                if (s.progress == null) {
                    logFile?.appendText("${s.phase}: ${s.detail}" + System.lineSeparator())
                }
            }

            try {
                say(InstallStatus(Phase.PREPARING, "Checking runtime and testing storage"))
                storage.ensureLayout()
                check(NativeRuntime.available) {
                    "Native runtime library missing: ${NativeRuntime.unavailableReason}"
                }
                val decision = storage.decide()
                val dir = storage.distroDir(entry.id)
                File(dir, "logs").mkdirs()
                File(dir, "config").mkdirs()
                logFile = File(dir, "logs/install.log")

                say(InstallStatus(Phase.RESOLVING, "Looking up the current ${entry.name} release"))
                val src = entry.resolve()
                val place = storage.placement(entry.id, decision.location)

                val margin = 32L * 1024 * 1024
                val dlFree = LinuxBootStorage.freeBytes(storage.downloads)
                if (dlFree < src.sizeBytes + margin) {
                    throw IOException(
                        "Not enough free space to download: need about " +
                            "${formatBytes(src.sizeBytes + margin)}, have ${formatBytes(dlFree)}",
                    )
                }
                place.base.mkdirs()
                val rootFree = LinuxBootStorage.freeBytes(place.base)
                if (rootFree < src.estimatedInstalledBytes + margin) {
                    throw IOException(
                        "Not enough free space to extract (estimated): need about " +
                            "${formatBytes(src.estimatedInstalledBytes + margin)}, have ${formatBytes(rootFree)}",
                    )
                }

                val meta = DistroMetadata(
                    id = entry.id,
                    name = entry.name,
                    version = src.version,
                    state = DistroState.INSTALLING,
                    location = decision.location,
                    rootfsPath = place.rootfs.path,
                    homePath = place.home.path,
                    sourceUrl = src.url,
                    sha256 = src.sha256,
                    installedAt = System.currentTimeMillis(),
                )
                store.save(meta)

                val archive = File(storage.downloads, src.fileName)
                val alreadyGood = archive.isFile && NativeRuntime.verifySha256(archive, src.sha256).isSuccess
                if (!alreadyGood) {
                    archive.delete()
                    say(InstallStatus(Phase.DOWNLOADING, "${src.fileName} (resumes if interrupted)"))
                    Downloader.download(src.url, archive, src.sizeBytes) { p ->
                        say(InstallStatus(Phase.DOWNLOADING, "", p))
                    }
                }

                say(InstallStatus(Phase.VERIFYING, src.sha256))
                NativeRuntime.verifySha256(archive, src.sha256).getOrElse {
                    archive.delete()
                    throw IOException("Checksum verification failed, download discarded: ${it.message}")
                }

                say(InstallStatus(Phase.EXTRACTING, "Into ${place.rootfs.path}"))
                Fs.deleteTree(place.rootfs)
                place.rootfs.mkdirs()
                rootfsToClean = place.rootfs
                val rep = NativeRuntime.extract(archive, place.rootfs).getOrElse {
                    throw IOException("Extraction failed: ${it.message}")
                }
                say(
                    InstallStatus(
                        Phase.EXTRACTING,
                        "${rep.files} files, ${rep.dirs} dirs, ${rep.symlinks} symlinks, " +
                            "${rep.hardlinks} hardlinks, ${rep.skipped} skipped",
                    ),
                )

                say(InstallStatus(Phase.CONFIGURING, "Writing DNS configuration, creating home"))
                configure(place)
                rootfsToClean = null

                store.save(meta.copy(state = DistroState.EXTRACTED))
                archive.delete()
                say(InstallStatus(Phase.DONE, "${entry.name} ${src.version} installed. Rootfs ready."))
                Result.success(Unit)
            } catch (e: CancellationException) {
                rootfsToClean?.let { runCatching { Fs.deleteTree(it) } }
                throw e
            } catch (e: Throwable) {
                rootfsToClean?.let { runCatching { Fs.deleteTree(it) } }
                val msg = e.message ?: e.javaClass.simpleName
                runCatching {
                    store.load(entry.id)?.let { store.save(it.copy(state = DistroState.FAILED, message = msg)) }
                }
                say(InstallStatus(Phase.FAILED, msg))
                Result.failure(e)
            }
        }

    private fun configure(place: Placement) {
        val etc = File(place.rootfs, "etc")
        etc.mkdirs()
        val resolv = etc.toPath().resolve("resolv.conf")
        Files.deleteIfExists(resolv)
        val text = buildString {
            appendLine("nameserver 1.1.1.1")
            appendLine("nameserver 8.8.8.8")
        }
        Files.write(resolv, text.toByteArray())
        place.home.mkdirs()
    }
}
