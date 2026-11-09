package linuxboot.distro

import linuxboot.storage.Fs
import linuxboot.storage.LinuxBootStorage
import linuxboot.storage.RootfsLocation
import org.json.JSONObject
import java.io.File
import java.io.IOException

enum class DistroState(val label: String) {
    INSTALLING("INSTALL INCOMPLETE"),
    EXTRACTED("INSTALLED"),
    FAILED("FAILED"),
}

data class DistroMetadata(
    val id: String,
    val name: String,
    val version: String,
    val state: DistroState,
    val location: RootfsLocation,
    val rootfsPath: String,
    val homePath: String,
    val sourceUrl: String,
    val sha256: String,
    val installedAt: Long,
    val message: String? = null,
    val desktop: String? = null,
    val vncPassword: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("version", version)
        .put("state", state.name)
        .put("location", location.name)
        .put("rootfsPath", rootfsPath)
        .put("homePath", homePath)
        .put("sourceUrl", sourceUrl)
        .put("sha256", sha256)
        .put("installedAt", installedAt)
        .put("message", message)
        .put("desktop", desktop)
        .put("vncPassword", vncPassword)

    companion object {
        fun fromJson(o: JSONObject) = DistroMetadata(
            id = o.getString("id"),
            name = o.getString("name"),
            version = o.getString("version"),
            state = DistroState.valueOf(o.getString("state")),
            location = RootfsLocation.valueOf(o.getString("location")),
            rootfsPath = o.getString("rootfsPath"),
            homePath = o.getString("homePath"),
            sourceUrl = o.getString("sourceUrl"),
            sha256 = o.getString("sha256"),
            installedAt = o.getLong("installedAt"),
            message = if (o.has("message")) o.getString("message") else null,
            desktop = if (o.has("desktop")) o.getString("desktop") else null,
            vncPassword = if (o.has("vncPassword")) o.getString("vncPassword") else null,
        )
    }
}

/** Reads and writes distributions/<id>/metadata.json and measures or removes a distribution. */
class DistroStore(private val storage: LinuxBootStorage) {
    private fun metaFile(id: String) = File(storage.distroDir(id), "metadata.json")

    fun save(m: DistroMetadata) {
        val f = metaFile(m.id)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "metadata.json.tmp")
        tmp.writeText(m.toJson().toString(2))
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("Cannot write metadata for ${m.id}")
        }
    }

    fun load(id: String): DistroMetadata? = try {
        metaFile(id).takeIf { it.isFile }?.let { DistroMetadata.fromJson(JSONObject(it.readText())) }
    } catch (e: Exception) {
        null
    }

    fun list(): List<DistroMetadata> =
        storage.distributions.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { load(it.name) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Real bytes on disk for this distribution, across both storage locations. */
    fun usage(m: DistroMetadata): Long {
        val base = File(m.rootfsPath).parentFile ?: return 0L
        val meta = storage.distroDir(m.id)
        return if (base.canonicalPath == meta.canonicalPath) {
            Fs.treeSize(meta)
        } else {
            Fs.treeSize(base) + Fs.treeSize(meta)
        }
    }

    /** Deletes the rootfs, home, logs and metadata. Refuses paths outside LinuxBoot's own storage. */
    fun remove(id: String): Result<Unit> = runCatching {
        val meta = load(id)
        val base = meta?.let { File(it.rootfsPath).parentFile }
        if (base != null) {
            check(storage.owns(base)) { "Refusing to delete outside LinuxBoot storage: ${base.path}" }
            Fs.deleteTree(base)
        }
        Fs.deleteTree(storage.distroDir(id))
    }
}
