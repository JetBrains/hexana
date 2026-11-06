package org.jetbrains.hexana.io

import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Memory-maps the file at [path] read-only as [LargeBytes], for files of any size.
 *
 * One `MappedByteBuffer` is capped at 2 GiB, so the file is mapped in chunks, lazily, as reads
 * reach them. Chunks start every [CHUNK_STEP] bytes and run [CHUNK_OVERLAP] bytes into the next,
 * so any [window] of up to [CHUNK_OVERLAP] bytes lies inside one chunk and is a zero-copy view;
 * a larger window gets a mapping of its own. Mapping costs address space, not heap, and the
 * mappings outlive the channel used to create them, so no file handle is held open.
 *
 * The size is taken once, at construction: a file that grows later is seen at its old size, and
 * one that shrinks makes reads past its new end fail as they would for any mapping.
 */
class MappedFileBytes private constructor(
    private val path: Path,
    override val size: Long,
) : LargeBytes {
    private val chunks = AtomicReferenceArray<MappedByteBuffer?>(chunkCount(size))

    override fun get(offset: Long): Byte {
        if (offset < 0 || offset >= size) throw IndexOutOfBoundsException("offset $offset outside 0 until $size")
        val index = (offset / CHUNK_STEP).toInt()
        return chunk(index).get((offset - index * CHUNK_STEP).toInt())
    }

    override fun read(offset: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        checkRead(offset, length, size, dst, dstOffset)
        var done = 0
        while (done < length) {
            val at = offset + done
            val index = (at / CHUNK_STEP).toInt()
            val chunk = chunk(index)
            val inChunk = (at - index * CHUNK_STEP).toInt()
            val count = minOf(length - done, chunk.capacity() - inChunk)
            chunk.get(inChunk, dst, dstOffset + done, count)
            done += count
        }
    }

    override fun window(offset: Long, length: Int): CommonByteBuffer {
        checkWindow(offset, length, size)
        val index = (offset / CHUNK_STEP).toInt()
        val inChunk = offset - index * CHUNK_STEP
        if (index < chunks.length() && inChunk + length <= chunkLength(index)) {
            return chunk(index).toCommonByteBuffer().slice(inChunk.toInt(), length)
        }
        return map(offset, length.toLong()).toCommonByteBuffer()
    }

    override fun asCommonByteBufferOrNull(): CommonByteBuffer? =
        if (size <= Int.MAX_VALUE) window(0, size.toInt()) else null

    private fun chunk(index: Int): MappedByteBuffer {
        chunks.get(index)?.let { return it }
        val mapped = map(index * CHUNK_STEP, chunkLength(index))
        return if (chunks.compareAndSet(index, null, mapped)) mapped else chunks.get(index)!!
    }

    private fun chunkLength(index: Int): Long = minOf(CHUNK_STEP + CHUNK_OVERLAP, size - index * CHUNK_STEP)

    private fun map(offset: Long, length: Long): MappedByteBuffer =
        FileChannel.open(path, StandardOpenOption.READ).use { it.map(FileChannel.MapMode.READ_ONLY, offset, length) }

    companion object {
        /** Distance between chunk starts. */
        const val CHUNK_STEP: Long = 1L shl 30

        /** How far each chunk runs into the next; the largest window served without its own mapping. */
        const val CHUNK_OVERLAP: Long = 256L shl 20

        /** Maps [path] as it is now. */
        fun open(path: Path): MappedFileBytes {
            val size = FileChannel.open(path, StandardOpenOption.READ).use { it.size() }
            return MappedFileBytes(path, size)
        }

        private fun chunkCount(size: Long): Int = if (size == 0L) 0 else ((size - 1) / CHUNK_STEP + 1).toInt()
    }
}
