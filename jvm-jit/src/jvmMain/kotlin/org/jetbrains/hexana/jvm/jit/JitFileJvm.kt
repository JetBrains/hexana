package org.jetbrains.hexana.jvm.jit

import org.jetbrains.hexana.io.toCommonByteBuffer
import java.io.File

/**
 * JVM convenience: memory-mapping a dump file and handing the bytes to
 * [JitFile.parse]. Returns `null` if the file isn't a valid `"MIN"` dump.
 *
 * The whole file is read into a heap buffer rather than mmap'd because
 * `CommonByteBuffer` doesn't expose a release/unmap step yet — JVMTI dumps
 * are typically a few MB so the copy is fine in practice.
 */
fun JitFile.Companion.fromFile(file: File): JitFile? {
    val bytes = file.readBytes()
    return parse(bytes.toCommonByteBuffer())
}
