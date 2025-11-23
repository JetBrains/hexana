package org.jetbrains.hexana.graalwasm

import org.graalvm.polyglot.Value

class WasmModuleMemory(
    private val memory: Value
) {
    fun write(ptr: Int, bytes: ByteArray) {
        for (i in bytes.indices) {
            memory.setArrayElement((ptr + i).toLong(), bytes[i].toInt())
        }
    }

    fun read(ptr: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[i] = (memory.getArrayElement((ptr + i).toLong()).asInt() and 0xFF).toByte()
        }
        return out
    }
}