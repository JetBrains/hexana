package org.jetbrains.hexana.shared

/**
 * Decodes a raw Wasm gdbstub stack-value hex payload into a human-readable string.
 *
 * The gdbstub `qWasmStackValue:<frame>;<index>` and `qWasmLocal:<frame>;<index>`
 * packets return little-endian hex bytes. This function decodes them according to
 * the wasm value type supplied by the static analyzer.
 *
 * @param hex   the raw hex payload from the gdbstub response (e.g. `"03000000"`).
 * @param typeDisplay the analyzer's type string for this slot
 *              (`"i32"`, `"i64"`, `"f32"`, `"f64"`).
 * @return a decimal/float string, or `null` when the payload is
 *         empty, the wrong length for the type, or the type is unrecognised.
 */
fun decodeWasmValueHex(hex: String, typeDisplay: String): String? {
    if (hex.isEmpty()) return null
    val bytes = try {
        if (hex.length % 2 != 0) return null
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    } catch (_: NumberFormatException) {
        return null
    }
    return when (typeDisplay) {
        "i32" -> {
            if (bytes.size != 4) return null
            val v = (bytes[0].toInt() and 0xFF) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)
            v.toString()
        }
        "i64" -> {
            if (bytes.size != 8) return null
            var v = 0L
            for (i in 0..7) v = v or ((bytes[i].toLong() and 0xFF) shl (i * 8))
            v.toString()
        }
        "f32" -> {
            if (bytes.size != 4) return null
            val bits = (bytes[0].toInt() and 0xFF) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)
            val f = Float.fromBits(bits)
            f.toString()
        }
        "f64" -> {
            if (bytes.size != 8) return null
            var bits = 0L
            for (i in 0..7) bits = bits or ((bytes[i].toLong() and 0xFF) shl (i * 8))
            val d = Double.fromBits(bits)
            d.toString()
        }
        else -> null
    }
}

/**
 * Extracts the hex payload from collected `process plugin packet send` output lines.
 *
 * Expected format (two lines from LLDB, possibly preceded by the echoed command):
 * ```
 *   packet: qWasmStackValue:0;0
 *   response: 03000000
 * ```
 *
 * @param lines the lines collected by `sendCommandAndCollectOutput`.
 * @return the trimmed hex string after `"response: "`, or `null` when the
 *         response line is absent or its value is empty.
 */
fun parseRawPacketResponse(lines: List<String>): String? {
    val responseLine = lines.lastOrNull { it.trimStart().startsWith("response:") } ?: return null
    val payload = responseLine.substringAfter("response:").trim()
    return if (payload.isEmpty()) null else payload
}
