package org.jetbrains.hexana.nativebin

import org.jetbrains.hexana.shared.FileEntry
import org.jetbrains.hexana.shared.FileSystem
import java.io.File

/** `java.io.File`-based adapter for the shared [FileSystem] interface. */
internal val JVM_FILE_SYSTEM = object : FileSystem {
    override fun listEntries(dir: String): List<FileEntry> {
        val files = File(dir).listFiles() ?: return emptyList()
        return files.map { FileEntry(it.name, it.isDirectory, it.isFile) }
    }

    override fun readFile(path: String): ByteArray? {
        val file = File(path)
        return if (file.exists()) file.readBytes() else null
    }

    override fun fileExists(path: String): Boolean = File(path).exists()

    override fun regularFileSize(path: String): Long? =
        File(path).takeIf { it.isFile }?.length()

    override fun parentDir(path: String): String = File(path).parent ?: ""

    override fun joinPath(base: String, child: String): String = File(base, child).path

    override fun pathRoot(path: String): String {
        var f = File(path).absoluteFile
        while (f.parentFile != null) f = f.parentFile
        return f.path
    }
}
