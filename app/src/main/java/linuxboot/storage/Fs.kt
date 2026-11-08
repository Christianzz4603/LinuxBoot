package linuxboot.storage

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.io.File

/**
 * Tree helpers that never follow symlinks. File.deleteRecursively() and
 * File.walk() follow symlinked directories, which is unsafe inside a Linux rootfs.
 */
object Fs {
    fun deleteTree(root: File) {
        val p = root.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun treeSize(root: File): Long {
        val p = root.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return 0L
        var total = 0L
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                FileVisitResult.CONTINUE
        })
        return total
    }
}
