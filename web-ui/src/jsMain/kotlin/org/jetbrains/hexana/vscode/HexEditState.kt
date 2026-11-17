package org.jetbrains.hexana.vscode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer

/**
 * Tracks in-memory byte edits for the hex viewer.
 *
 * Maintains a mutable copy of the original bytes and an undo stack of
 * individual byte replacements. The [modificationStamp] increments on
 * every edit or undo so that Compose recomposes dependent UI.
 */
class HexEditState(private val originalBytes: ByteArray) {

    /** Working copy of the bytes with all edits applied. */
    private val editedBytes: ByteArray = originalBytes.copyOf()

    /** Undo stack: each entry is (offset, previousByte). */
    private val undoStack = mutableListOf<Pair<Int, Byte>>()

    /** Set of byte offsets that have been modified (for highlight). */
    var modifiedOffsets by mutableStateOf(emptySet<Int>())
        private set

    /** Monotonically increasing stamp that changes on every edit/undo. */
    var modificationStamp by mutableStateOf(0L)
        private set

    /** Whether there are any uncommitted edits. */
    val hasEdits: Boolean get() = undoStack.isNotEmpty()

    /** Returns the current (edited) byte at [offset]. */
    fun getByte(offset: Int): Byte = editedBytes[offset]

    /** Returns the full edited byte array (copy). */
    fun getEditedBytes(): ByteArray = editedBytes.copyOf()

    /** Returns the original byte array (copy). */
    fun getOriginalBytes(): ByteArray = originalBytes.copyOf()

    /** Returns a [CommonByteBuffer] wrapping the current edited bytes. */
    fun toBuffer(): CommonByteBuffer {
        val copy = editedBytes.copyOf()
        return copy.toCommonByteBuffer()
    }

    /** Total number of bytes. */
    val size: Int get() = editedBytes.size

    /**
     * Replaces the byte at [offset] with [value]. Pushes the previous
     * byte onto the undo stack.
     */
    fun replaceByte(offset: Int, value: Byte) {
        if (offset < 0 || offset >= editedBytes.size) return
        val previous = editedBytes[offset]
        undoStack.add(offset to previous)
        editedBytes[offset] = value
        modifiedOffsets = modifiedOffsets + offset
        modificationStamp++
    }

    /**
     * Discards all edits, restoring the original bytes.
     */
    fun discardAll() {
        originalBytes.copyInto(editedBytes)
        undoStack.clear()
        modifiedOffsets = emptySet()
        modificationStamp++
    }

    /**
     * Called after a successful overwrite to reset the edit state.
     * The current edited bytes become the new "original" baseline,
     * and the undo stack is cleared.
     */
    fun markSaved() {
        undoStack.clear()
        modifiedOffsets = emptySet()
        modificationStamp++
    }

    /**
     * Stores the original bytes at modified positions so that an
     * overwrite can be undone by the extension host.
     */
    fun captureOverwriteShadow(): OverwriteShadow {
        val entries = modifiedOffsets.sorted().map { offset ->
            OverwriteShadow.Entry(offset, originalBytes[offset])
        }
        return OverwriteShadow(originalBytes.size, entries)
    }
}

/**
 * Lightweight record of original bytes at modified positions, used to
 * support "Undo overwrite" after the file has been written to disk.
 */
class OverwriteShadow(
    val originalLength: Int,
    val entries: List<Entry>,
) {
    data class Entry(val offset: Int, val originalByte: Byte)

    /**
     * Reconstructs the original file content by patching [current]
     * with the stored original bytes.
     */
    fun applyTo(current: ByteArray): ByteArray {
        val result = if (current.size == originalLength) current.copyOf()
        else ByteArray(originalLength)
        for (entry in entries) {
            if (entry.offset < result.size) {
                result[entry.offset] = entry.originalByte
            }
        }
        return result
    }
}
