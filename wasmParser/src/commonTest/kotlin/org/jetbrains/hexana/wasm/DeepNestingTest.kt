package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Flow
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deep-nesting stress for the opcode parser: nesting depth must be bounded by
 * heap, not by the platform call stack. 100k nested blocks overflow a plain
 * recursive descent on every target; the DeepRecursiveFunction-based parser
 * must handle them.
 */
class DeepNestingTest {

    @Test
    fun `parses 100k nested empty blocks without stack overflow`() {
        val depth = 100_000
        val bytes = ByteArray(depth * 3)
        var i = 0
        repeat(depth) {
            bytes[i++] = 0x02 // block
            bytes[i++] = 0x40 // empty block type
        }
        repeat(depth) {
            bytes[i++] = 0x0B // end
        }

        val ops = bytes.toCommonByteBuffer().parseOpCodes()

        assertEquals(1, ops.size)
        // Walk down iteratively; Block.equals/hashCode recurse through the body,
        // so no deep structural comparisons here.
        var measuredDepth = 0
        var current: List<OpCode> = ops
        while (current.isNotEmpty()) {
            val block = current.single() as Flow.Block
            measuredDepth++
            current = block.value
        }
        assertEquals(depth, measuredDepth)
    }
}