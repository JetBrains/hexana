package org.jetbrains.hexana

interface BufferWrapperInterface

open class BufferWrapper<T>(internal val buffer: T): BufferWrapperInterface
expect class CommonByteBuffer : BufferWrapperInterface {
    fun get(): Byte
    fun get(dst: ByteArray, offset: Int, size: Int): CommonByteBuffer
    fun getULEB128(): Long
    fun getSLEB128(): Long
    fun position(): Int
    fun position(index: Int): CommonByteBuffer
    fun remaining(): Int
    fun hasRemaining(): Boolean
    fun getFloatLE(): Float
}

