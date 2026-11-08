package linuxboot.storage

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** Outcome of testing whether a directory can hold a Linux rootfs. */
data class ProbeResult(
    val symlink: Boolean,
    val chmod: Boolean,
    val hardlink: Boolean,
    val caseSensitive: Boolean,
    val canExec: Boolean,
    val errors: List<String>,
) {
    /** Everything PRoot and typical rootfs archives need. canExec is informational only. */
    val suitable: Boolean get() = symlink && chmod && hardlink && caseSensitive

    fun toJson(): JSONObject = JSONObject()
        .put("symlink", symlink)
        .put("chmod", chmod)
        .put("hardlink", hardlink)
        .put("caseSensitive", caseSensitive)
        .put("canExec", canExec)
        .put("errors", JSONArray(errors))

    companion object {
        fun fromJson(o: JSONObject): ProbeResult {
            val arr = o.optJSONArray("errors")
            val errs = (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
            return ProbeResult(
                o.getBoolean("symlink"),
                o.getBoolean("chmod"),
                o.getBoolean("hardlink"),
                o.getBoolean("caseSensitive"),
                o.getBoolean("canExec"),
                errs,
            )
        }
    }
}

/** Runs real filesystem operations in [dir]; nothing is assumed about the storage type. */
object StorageProbe {
    fun run(dir: File): ProbeResult {
        val errors = mutableListOf<String>()

        fun check(name: String, block: () -> Boolean): Boolean = try {
            block().also { if (!it) errors += "$name: unexpected result" }
        } catch (e: Throwable) {
            errors += "$name: ${e.javaClass.simpleName}: ${e.message}"
            false
        }

        try {
            Fs.deleteTree(dir)
            dir.mkdirs()

            val symlink = check("symlink") {
                File(dir, "target").writeText("x")
                val link = Paths.get(dir.path, "link")
                Files.createSymbolicLink(link, Paths.get("target"))
                Files.isSymbolicLink(link) && Files.readSymbolicLink(link).toString() == "target"
            }

            val script = File(dir, "exec.sh")
            val chmod = check("chmod") {
                script.writeText("#!/system/bin/sh" + System.lineSeparator() + "exit 0" + System.lineSeparator())
                Os.chmod(script.path, 0x1ED) // 0755
                (Os.stat(script.path).st_mode and 0x1FF) == 0x1ED
            }

            val hardlink = check("hardlink") {
                val a = File(dir, "a")
                a.writeText("x")
                Files.createLink(Paths.get(dir.path, "b"), a.toPath())
                File(dir, "b").exists()
            }

            val caseSensitive = check("case-sensitivity") {
                File(dir, "Case").writeText("1")
                File(dir, "case").writeText("2")
                (dir.list() ?: emptyArray()).count { it.equals("case", ignoreCase = true) } == 2
            }

            val canExec = try {
                chmod && ProcessBuilder(script.path).start().waitFor() == 0
            } catch (e: Throwable) {
                false
            }

            return ProbeResult(symlink, chmod, hardlink, caseSensitive, canExec, errors)
        } finally {
            runCatching { Fs.deleteTree(dir) }
        }
    }
}
