package org.jetbrains.hexana

import com.intellij.testFramework.UsefulTestCase

class HexanaUtilTest : UsefulTestCase() {

    fun testConvertSearchTextToBytes() {
        assertOrderedEquals(byteArrayOf(), convertSearchTextToBytes(""))
        assertOrderedEquals(byteArrayOf(), convertSearchTextToBytes("not a hex string"))

        for (i in 0 until UByte.MAX_VALUE.toInt()) {
            val b = i.toByte()
            val text = b.toHexString()
            assertOrderedEquals(byteArrayOf(b), convertSearchTextToBytes(text))
        }

        assertOrderedEquals(byteArrayOf(0x10), convertSearchTextToBytes("1"))
        assertOrderedEquals(byteArrayOf(0x12, 0x30), convertSearchTextToBytes("123"))

        assertOrderedEquals(byteArrayOf(0x12, 0x34, 0x56), convertSearchTextToBytes("12 34  56"))
    }


    fun testFind() {
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
        assertEquals(0, find(hay, hay))

        assertEquals(-1, find(byteArrayOf(0x11), hay))
        assertEquals(-1, find(byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
        ), hay))
    }


    fun testFindReverse() {
        val hay = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x01, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
        )

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
        assertEquals(0, find(hay, hay, reverse = true))

        assertEquals(-1, find(byteArrayOf(0x11), hay, reverse = true))
        assertEquals(-1, find(byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
        ), hay, reverse = true))
    }
}

