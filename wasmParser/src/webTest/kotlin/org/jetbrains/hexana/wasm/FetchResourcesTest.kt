package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals

class FetchResourcesTest {
    @Test
    fun resourceAsByteBufferTest() = runTest {
        val commonByteBuffer = resourceAsByteBuffer("skiko.wasm")

        val bytes = ByteArray(4)
        commonByteBuffer.get(bytes, 0, 4)

        assertContentEquals(bytes, byteArrayOf(0x00, 0x61, 0x73, 0x6D))
    }
}