package org.jetbrains.hexana.run

import org.jetbrains.hexana.shared.StubFileWriter
import java.io.File
import java.nio.file.Files

/**
 * JVM implementation of [StubFileWriter] that creates temporary
 * directories and files using `java.nio.file` APIs.
 *
 * Created files and directories are registered for deletion on JVM
 * exit via [File.deleteOnExit].
 */
internal object JvmStubFileWriter : StubFileWriter {

    override fun createTempDir(prefix: String): String {
        val dir = Files.createTempDirectory(prefix).toFile()
        dir.deleteOnExit()
        return dir.absolutePath
    }

    override fun writeFile(dir: String, fileName: String, bytes: ByteArray): String {
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file.absolutePath
    }
}
