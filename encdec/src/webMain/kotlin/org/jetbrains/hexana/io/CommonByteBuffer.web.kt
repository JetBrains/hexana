package org.jetbrains.hexana.io

actual class CommonByteBuffer : BufferWrapperInterface {
    fun get(): Byte {
        TODO("Not yet implemented")
    }

    fun get(dst: ByteArray, offset: Int, size: Int): CommonByteBuffer {
        TODO("Not yet implemented")
    }

    fun getULEB128(): Long {
        TODO("Not yet implemented")
    }

    fun position(): Int {
        TODO("Not yet implemented")
    }

    fun position(index: Int): CommonByteBuffer {
        TODO("Not yet implemented")
    }

    fun remaining(): Int {
        TODO("Not yet implemented")
    }

    fun hasRemaining(): Boolean {
        TODO("Not yet implemented")
    }

    fun getSLEB128(): Long {
        TODO("Not yet implemented")
    }

    fun getFloatLE(): Float {
        TODO("Not yet implemented")
    }
}