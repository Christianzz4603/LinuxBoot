package linuxboot.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.IOException

enum class RootfsLocation { EXTERNAL, INTERNAL }

/** Where a distribution's rootfs and home live. */
data class Placement(val base: File, val rootfs: File, val home: File)

data class StorageDecision(
    val location: RootfsLocation,
    val probe: ProbeResult,
    val probedAt: Long,
    val externalAvailable: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("rootfsLocation", location.name)
        .put("probedAt", probedAt)
        .put("externalAvailable", externalAvailable)
        .put("probe", probe.toJson())

    companion object {
        fun fromJson(o: JSONObject) = StorageDecision(
            RootfsLocation.valueOf(o.getString("rootfsLocation")),
            ProbeResult.fromJson(o.getJSONObject("probe")),
            o.getLong("probedAt"),
            o.getBoolean("externalAvailable"),
        )
    }
}

/**
 * Owns the LinuxBoot directory layout.
 *
 * Downloads, backups, cache, config, logs and metadata live in app-specific external
 * storage (getExternalFilesDir). The rootfs and home also go there when a real
 * filesystem test passes; otherwise ONLY those two move to internal app storage.
 */
class LinuxBootStorage(context: Context) {
    private val internalBase = File(context.filesDir, "LinuxBoot")
    private val externalBase: File? = context.getExternalFilesDir(null)
        ?.takeIf { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
        ?.let { File(it, "LinuxBoot") }

    val isExternal: Boolean = externalBase != null
    val dataRoot: File = externalBase ?: internalBase

    val distributions = File(dataRoot, "distributions")
    val downloads = File(dataRoot, "downloads")
    val backups = File(dataRoot, "backups")
    val cache = File(dataRoot, "cache")
    val boot = File(dataRoot, "boot")
    val config = File(dataRoot, "config")

    fun ensureLayout() {
        listOf(distributions, downloads, backups, cache, boot, config).forEach {
            if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create ${it.path}")
        }
    }

    fun distroDir(id: String): File {
        require(ID.matches(id)) { "Invalid distribution id" }
        return File(distributions, id)
    }

    fun placement(id: String, location: RootfsLocation): Placement {
        val base = when (location) {
            RootfsLocation.EXTERNAL -> distroDir(id)
            RootfsLocation.INTERNAL -> File(internalBase, "distributions/$id")
        }
        return Placement(base, File(base, "rootfs"), File(base, "home"))
    }

    /** True if [f] is inside a directory LinuxBoot manages. Guards deletions. */
    fun owns(f: File): Boolean {
        val p = f.canonicalPath + File.separator
        return p.startsWith(dataRoot.canonicalPath + File.separator) ||
            p.startsWith(internalBase.canonicalPath + File.separator)
    }

    fun loadDecision(): StorageDecision? {
        val f = File(config, "storage.json")
        if (!f.isFile) return null
        return try {
            StorageDecision.fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the saved decision, or runs the filesystem probe and saves a new one. */
    fun decide(force: Boolean = false): StorageDecision {
        if (!force) loadDecision()?.let { return it }
        ensureLayout()
        val result = StorageProbe.run(File(cache, ".probe"))
        val location =
            if (isExternal && result.suitable) RootfsLocation.EXTERNAL else RootfsLocation.INTERNAL
        val decision = StorageDecision(location, result, System.currentTimeMillis(), isExternal)
        File(config, "storage.json").writeText(decision.toJson().toString(2))
        return decision
    }

    companion object {
        private val ID = Regex("[a-z0-9_-]{1,32}")

        fun freeBytes(dir: File): Long {
            var d: File? = dir
            while (d != null && !d.exists()) d = d.parentFile
            return if (d == null) 0L else StatFs(d.path).availableBytes
        }
    }
}
