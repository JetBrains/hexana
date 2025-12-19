package org.jetbrains.hexana

fun convertSearchTextToBytes(text: String): ByteArray {
    val byteList = arrayListOf<Byte>()
    val s = text.replace(" ", "")
    for (i in s.indices step 2) {
        val c1 = s[i]
        val c2 = if (i+1 in s.indices) {
            s[i+1]
        } else {
            '0'
        }
        val upper = c1.digitToIntOrNull(16) ?: return ByteArray(0)
        val lower = c2.digitToIntOrNull(16) ?: return ByteArray(0)
        val byte = ((upper shl 4) or lower).toByte()
        byteList.add(byte)
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
