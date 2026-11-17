package org.jetbrains.hexana.wasm.analysis.diff

import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import kotlin.test.Test
import kotlin.test.assertEquals

class BinarySizeDiffTest {

    @Test
    fun identicalTrees() {
        val left = BinarySizeNode("ELF", 1000, 0, listOf(
            BinarySizeNode(".text", 500, 0),
            BinarySizeNode(".data", 300, 500),
        ))
        val right = BinarySizeNode("ELF", 1000, 0, listOf(
            BinarySizeNode(".text", 500, 0),
            BinarySizeNode(".data", 300, 500),
        ))
        val diff = BinarySizeDiff.diff(left, right)
        assertEquals(DiffClass.IDENTICAL, diff.classification)
        assertEquals(0, diff.delta)
        assertEquals(2, diff.children.size)
        diff.children.forEach { assertEquals(DiffClass.IDENTICAL, it.classification) }
    }

    @Test
    fun modifiedSection() {
        val left = BinarySizeNode("ELF", 1000, 0, listOf(
            BinarySizeNode(".text", 500, 0),
        ))
        val right = BinarySizeNode("ELF", 1200, 0, listOf(
            BinarySizeNode(".text", 700, 0),
        ))
        val diff = BinarySizeDiff.diff(left, right)
        assertEquals(DiffClass.MODIFIED, diff.classification)
        assertEquals(200, diff.delta)
        assertEquals(1, diff.children.size)
        assertEquals(DiffClass.MODIFIED, diff.children[0].classification)
        assertEquals(500, diff.children[0].sizeA)
        assertEquals(700, diff.children[0].sizeB)
    }

    @Test
    fun addedAndRemovedSections() {
        val left = BinarySizeNode("ELF", 800, 0, listOf(
            BinarySizeNode(".text", 500, 0),
            BinarySizeNode(".rodata", 300, 500),
        ))
        val right = BinarySizeNode("ELF", 900, 0, listOf(
            BinarySizeNode(".text", 500, 0),
            BinarySizeNode(".bss", 400, 500),
        ))
        val diff = BinarySizeDiff.diff(left, right)
        assertEquals(DiffClass.MODIFIED, diff.classification)
        assertEquals(3, diff.children.size)

        val text = diff.children[0]
        assertEquals(".text", text.displayName)
        assertEquals(DiffClass.IDENTICAL, text.classification)

        val rodata = diff.children[1]
        assertEquals(".rodata", rodata.displayName)
        assertEquals(DiffClass.REMOVED, rodata.classification)
        assertEquals(300, rodata.sizeA)
        assertEquals(null, rodata.sizeB)

        val bss = diff.children[2]
        assertEquals(".bss", bss.displayName)
        assertEquals(DiffClass.ADDED, bss.classification)
        assertEquals(null, bss.sizeA)
        assertEquals(400, bss.sizeB)
    }

    @Test
    fun nestedChildren() {
        val left = BinarySizeNode("Mach-O", 2000, 0, listOf(
            BinarySizeNode("__TEXT", 1000, 0, listOf(
                BinarySizeNode("__text", 800, 0),
                BinarySizeNode("__cstring", 200, 800),
            )),
        ))
        val right = BinarySizeNode("Mach-O", 2200, 0, listOf(
            BinarySizeNode("__TEXT", 1200, 0, listOf(
                BinarySizeNode("__text", 900, 0),
                BinarySizeNode("__cstring", 200, 900),
                BinarySizeNode("__const", 100, 1100),
            )),
        ))
        val diff = BinarySizeDiff.diff(left, right)
        assertEquals(DiffClass.MODIFIED, diff.classification)
        val textSeg = diff.children[0]
        assertEquals("__TEXT", textSeg.displayName)
        assertEquals(DiffClass.MODIFIED, textSeg.classification)
        assertEquals(3, textSeg.children.size)

        assertEquals(DiffClass.MODIFIED, textSeg.children[0].classification) // __text grew
        assertEquals(DiffClass.IDENTICAL, textSeg.children[1].classification) // __cstring same
        assertEquals(DiffClass.ADDED, textSeg.children[2].classification) // __const new
    }
}
