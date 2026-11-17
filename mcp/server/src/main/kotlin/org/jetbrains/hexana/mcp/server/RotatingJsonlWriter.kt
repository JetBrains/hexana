package org.jetbrains.hexana.mcp.server

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Appends one line per record to [file] and rolls it over once it reaches [maxFileSizeBytes].
 *
 * Rollover shifts `name.jsonl` to `name.1.jsonl`, `name.1.jsonl` to `name.2.jsonl` and so on up to
 * [maxRolledIndex]; the oldest rolled file is dropped.
 */
internal class RotatingJsonlWriter(
    private val file: Path,
    private val maxFileSizeBytes: Long,
    private val maxRolledIndex: Int,
) : AutoCloseable {
    private var output: OutputStream
    private var size: Long

    init {
        require(maxFileSizeBytes > 0)
        require(maxRolledIndex >= 1)
        output = openOutput()
        size = Files.size(file)
    }

    /** Returns true when this append rolled the file over. */
    @Synchronized
    fun append(line: String): Boolean {
        val bytes = "$line\n".toByteArray(StandardCharsets.UTF_8)
        val rolled = size >= maxFileSizeBytes
        if (rolled) {
            rollOver()
        }
        output.write(bytes)
        output.flush()
        size += bytes.size
        return rolled
    }

    @Synchronized
    override fun close() {
        output.close()
    }

    private fun rollOver() {
        output.close()
        Files.deleteIfExists(rolledFile(maxRolledIndex))
        for (index in maxRolledIndex - 1 downTo 1) {
            val source = rolledFile(index)
            if (Files.exists(source)) {
                Files.move(source, rolledFile(index + 1), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Files.move(file, rolledFile(1), StandardCopyOption.REPLACE_EXISTING)
        output = openOutput()
        size = 0
    }

    private fun rolledFile(index: Int): Path =
        file.resolveSibling(file.fileName.toString().removeSuffix(".jsonl") + ".$index.jsonl")

    private fun openOutput(): OutputStream =
        Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
}
