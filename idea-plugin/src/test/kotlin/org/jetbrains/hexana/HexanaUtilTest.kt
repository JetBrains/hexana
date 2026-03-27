package org.jetbrains.hexana

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import java.nio.ByteBuffer

class HexanaUtilTest : UsefulTestCase() {

    fun testConvertSearchTextToBytes() {
        assertOrderedEquals(byteArrayOf(), convertSearchTextToBytes(""))
        assertOrderedEquals("text".toByteArray(), convertSearchTextToBytes("text"))

        for (i in 0 until UByte.MAX_VALUE.toInt()) {
            val b = i.toByte()
            val text = (b.toInt() and 0xFF).toString(16).padStart(2, '0')
            assertOrderedEquals(byteArrayOf(b), convertSearchTextToBytes(text))
        }

        assertOrderedEquals("1".toByteArray(), convertSearchTextToBytes("1"))
        assertOrderedEquals("123".toByteArray(), convertSearchTextToBytes("123"))

        assertOrderedEquals(byteArrayOf(0x12, 0x34, 0x56), convertSearchTextToBytes("12 34  56"))
    }
    
    fun testConvertSearchTextToBytesBinary() {
        assertOrderedEquals(byteArrayOf(0x12, 0x34), convertSearchTextToBytes("1234", true))
        assertOrderedEquals(byteArrayOf(0x12, 0x30), convertSearchTextToBytes("123", true))
        assertOrderedEquals(byteArrayOf(0x12, 0x34, 0x50), convertSearchTextToBytes("12 34 5", true))
        assertOrderedEquals(byteArrayOf(), convertSearchTextToBytes("G", true))
        assertOrderedEquals(byteArrayOf(), convertSearchTextToBytes("12 G", true))
    }


    fun testFind() {
        val hayArray = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x01, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
        )
        val hay = hayArray.wrapArray()

        assertEquals(0, find(byteArrayOf(), hay))
        for (i in hay.indices) {
            assertEquals(i, find(byteArrayOf(), hay, i))
        }
        assertEquals(0, find(byteArrayOf(0x00), hay))
        assertEquals(1, find(byteArrayOf(0x01), hay))
        assertEquals(15, find(byteArrayOf(0x0f), hay))
        assertEquals(4, find(byteArrayOf(0x01), hay, 2))

        assertEquals(1, find(byteArrayOf(0x01, 0x02), hay))
        assertEquals(14, find(byteArrayOf(0x0e, 0x0f), hay))
        assertEquals(0, find(hayArray, hay))

        assertEquals(-1, find(byteArrayOf(0x11), hay))
        assertEquals(-1, find(byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
        ), hay))
    }

    fun testFindOriginal() {
        val hay = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x01, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
        )

        assertEquals(0, find(byteArrayOf(), hay))
        for (i in hay.indices) {
            assertEquals(i, find(byteArrayOf(), hay, i))
        }
        assertEquals(0, find(byteArrayOf(0x00), hay))
        assertEquals(1, find(byteArrayOf(0x01), hay))
        assertEquals(15, find(byteArrayOf(0x0f), hay))
        assertEquals(4, find(byteArrayOf(0x01), hay, 2))

        assertEquals(1, find(byteArrayOf(0x01, 0x02), hay))
        assertEquals(14, find(byteArrayOf(0x0e, 0x0f), hay))
        assertEquals(0, find(
            byteArrayOf(
                0x00, 0x01, 0x02, 0x03, 0x01, 0x05, 0x06, 0x07,
                0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
            ), hay))

        assertEquals(-1, find(byteArrayOf(0x11), hay))
        assertEquals(-1, find(byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
        ), hay))
    }


    fun testFindReverse() {
        val hayArray = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x01, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
        )
        val hay = hayArray.wrapArray()

        assertEquals(hay.lastIndex, find(byteArrayOf(), hay, reverse = true))
        for (i in hay.indices) {
            assertEquals(i, find(byteArrayOf(), hay, i, reverse = true))
        }

        assertEquals(0, find(byteArrayOf(0x00), hay, reverse = true))
        assertEquals(4, find(byteArrayOf(0x01), hay, reverse = true))
        assertEquals(1, find(byteArrayOf(0x01), hay, startIndex = 3, reverse = true))
        assertEquals(15, find(byteArrayOf(0x0f), hay, reverse = true))

        assertEquals(1, find(byteArrayOf(0x01, 0x02), hay, reverse = true))
        assertEquals(14, find(byteArrayOf(0x0e, 0x0f), hay, reverse = true))
        assertEquals(0, find(hayArray, hay, reverse = true))

        assertEquals(-1, find(byteArrayOf(0x11), hay, reverse = true))
        assertEquals(-1, find(byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
        ), hay, reverse = true))
    }
}

private fun ByteArray.wrapArray(): CommonByteBuffer = ByteBuffer.wrap(this).toCommonByteBuffer()

