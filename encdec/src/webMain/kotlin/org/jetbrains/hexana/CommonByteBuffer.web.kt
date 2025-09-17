package org.jetbrains.hexana

actual class CommonByteBuffer : BufferWrapperInterface {
    actual fun get(): Byte {
        TODO("Not yet implemented")
    }

    actual fun get(dst: ByteArray, offset: Int, size: Int): CommonByteBuffer {
        TODO("Not yet implemented")
    }

    actual fun getULEB128(): Long {
        TODO("Not yet implemented")
    }

    actual fun position(): Int {
        TODO("Not yet implemented")
    }

    actual fun position(index: Int): CommonByteBuffer {
        TODO("Not yet implemented")
    }

    actual fun remaining(): Int {
        TODO("Not yet implemented")
    }

    actual fun hasRemaining(): Boolean {
        TODO("Not yet implemented")
    }

    actual fun getSLEB128(): Long {
        TODO("Not yet implemented")
    }
}