package org.jetbrains.hexana

actual class CommonByteBuffer(buffer: Any) : BufferWrapper<Any>(buffer)

actual fun CommonByteBuffer.get(): Byte {
    TODO("Not yet implemented")
}

actual fun CommonByteBuffer.getULEB128(): Long {
    TODO("Not yet implemented")
}

actual fun CommonByteBuffer.position(index: Int): CommonByteBuffer {
    TODO("Not yet implemented")
}

actual fun CommonByteBuffer.position(): Int {
    TODO("Not yet implemented")
}

actual fun CommonByteBuffer.remaining(): Int {
    TODO("Not yet implemented")
}

actual fun CommonByteBuffer.get(
    dst: ByteArray,
    offset: Int,
    size: Int
) {
    TODO("Not yet implemented")
}

actual fun CommonByteBuffer.hasRemaining(): Boolean {
    TODO("Not yet implemented")
}