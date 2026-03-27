package org.jetbrains.hexana

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.io.path.fileSize

fun convertSearchTextToBytes(text: String, isBinaryMode: Boolean = false): ByteArray {
    if (text.isEmpty()) return ByteArray(0)
    val byteList = arrayListOf<Byte>()
    val s = text.replace(" ", "")
    var isHex = isBinaryMode || (s.length % 2 == 0 && s.isNotEmpty())
    if (isHex) {
        for (i in s.indices step 2) {
            val c1 = s[i]
            val c2 = if (i + 1 < s.length) s[i + 1] else null
            val upper = c1.digitToIntOrNull(16)
            val lower = c2?.digitToIntOrNull(16) ?: 0
            if (upper == null || (c2 != null && lower == null)) {
                if (isBinaryMode) return ByteArray(0) // Invalid hex in binary mode
                isHex = false
                break
            }
            val byte = ((upper shl 4) or lower).toByte()
            byteList.add(byte)
        }
    }
    if (!isHex) {
        return text.toByteArray(Charsets.UTF_8)
    }
    return byteList.toByteArray()
}


fun find(
    needle: ByteArray,
    hay: ByteArray,
    startIndex: Int? = null,
    reverse: Boolean = false,
): Int {
    val start = when(startIndex) {
        null -> if (reverse) hay.lastIndex else 0
        else -> startIndex
    }.coerceIn(hay.indices)

    val step = if (reverse) -1 else 1
    var i = start
    while (i in hay.indices) {
        var match = true
        for (j in needle.indices) {
            if (hay[i + j] != needle[j]) {
                match = false
                break
            }
        }
        if (match) {
            return i
        }
        i += step
    }
    return -1
}

fun find(
    needle: ByteArray,
    hay: CommonByteBuffer,
    startIndex: Int? = null,
    reverse: Boolean = false,
): Int {
    val haySize = hay.limit()
    if (haySize == 0) return -1

    val start = when (startIndex) {
        null -> if (reverse) haySize - 1 else 0
        else -> startIndex
    }.coerceIn(hay.indices)

    val step = if (reverse) -1 else 1
    var i = start
    while (i in hay.indices) {
        if (i + needle.size > haySize) {
            i += step
            continue
        }
        hay.position(i)
        var match = true
        for (j in needle.indices) {
            if (hay.get() != needle[j]) {
                match = false
                break
            }
        }
        if (match) {
            return i
        }
        i += step
    }
    return -1
}


fun VirtualFile.toMappedBuffer(): MappedByteBuffer? {
    val filePath = toNioPath()
    val fileSize = filePath.fileSize()
    return FileChannel.open(filePath, StandardOpenOption.READ).use { fileChannel ->
        fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize)
    }
}
