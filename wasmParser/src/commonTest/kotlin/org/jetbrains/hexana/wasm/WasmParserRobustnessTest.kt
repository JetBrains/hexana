package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.components.Alias
import org.jetbrains.hexana.wasm.components.ComponentCoreInstance
import org.jetbrains.hexana.wasm.components.WasmComponentModuleSection
import org.jetbrains.hexana.wasm.components.WasmComponentSection
import org.jetbrains.hexana.wasm.components.WasmInstanceSection
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Flow
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes
import org.jetbrains.hexana.wasm.sections.WasmParseException
import org.jetbrains.hexana.wasm.sections.WasmSectionTypes
import org.jetbrains.hexana.wasm.sections.consumeArray
import org.jetbrains.hexana.wasm.sections.consumeArrayPartial
import org.jetbrains.hexana.wasm.sections.consumeCodeEntry
import org.jetbrains.hexana.wasm.sections.consumeString
import org.jetbrains.hexana.wasm.sections.consumeUnsignedUInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for parser robustness against malformed/adversarial input.
 *
 * The parser runs on untrusted, attacker-controlled `.wasm` bytes in the browser, so
 * every read/loop must make forward progress or fail cleanly instead of hanging or
 * exhausting memory. Each test below pins one such fix.
 */
class WasmParserRobustnessTest {

    @Test
    fun `parse records an invalid high-bit section size`() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, // magic
            0x01, 0x00, 0x00, 0x00, // version 1, layer 0 (core)
            0x40, // section id (unresolved)
            0xfa.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f,
        )
        val buffer = bytes.toCommonByteBuffer()

        val result = WasmParser().parse(buffer)

        assertEquals(listOf(8), assertIs<WasmParseSuccess>(result).sectionScanFailures.map { it.byteOffset })
    }

    @Test
    fun `parse exposes supported header identity without signed byte extension`() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x0d, 0x00, 0x81.toByte(), 0x00, // version 13, layer 0x0081
        )

        val result = assertIs<WasmParseSuccess>(WasmParser().parse(bytes.toCommonByteBuffer()))

        assertEquals(13u, result.header?.version)
        assertEquals(0x0081u, result.header?.layer)
    }

    @Test
    fun `parse records a declared section payload beyond the buffer`() {
        val result = assertIs<WasmParseSuccess>(
            byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x00, 0x7f, 0x00)
                .toCommonByteBuffer().let { WasmParser().parse(it) }
        )

        assertEquals(listOf(8), result.sectionScanFailures.map { it.byteOffset })
        assertTrue(assertIs<WasmModule>(result.wasmModule).sections.isEmpty())
    }

    @Test
    fun `parse rejects a truncated non-retained component section`() {
        val result = assertIs<WasmParseSuccess>(
            byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00, 0x08, 0x7f, 0x00)
                .toCommonByteBuffer().let { WasmParser().parse(it) }
        )

        assertEquals(listOf(8), result.sectionScanFailures.map { it.byteOffset })
        assertTrue(assertIs<ComponentModule>(result.wasmModule).sections.isEmpty())
    }

    @Test
    fun `parse distinguishes a malformed complete custom section name from truncation`() {
        val result = assertIs<WasmParseSuccess>(WasmParser().parse(byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00,
            0x00, 0x05, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10,
        ).toCommonByteBuffer()))

        assertEquals(SectionScanFailureKind.RESOLVER_FAILURE, result.sectionScanFailures.single().kind)
    }

    @Test
    fun `parse retains truncated component submodule ranges and records failure`() {
        listOf(WasmComponentModuleSection.SECTION_ID, WasmComponentSection.SECTION_ID).forEach { sectionId ->
            val bytes = byteArrayOf(
                0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00,
                sectionId, 0x40, 0x00, 0x61, 0x73, 0x6d,
            )
            val buffer = bytes.toCommonByteBuffer()
            val result = assertIs<WasmParseSuccess>(WasmParser().parse(buffer))
            val section = assertIs<ComponentModule>(result.wasmModule).sections.single()

            assertEquals(8, section.sectionOffset)
            assertEquals(10, section.contentOffset)
            assertEquals(0x40, section.contentSize)
            assertEquals(listOf(8), result.sectionScanFailures.map { it.byteOffset })
            when (sectionId) {
                WasmComponentModuleSection.SECTION_ID -> assertIs<WasmComponentModuleSection>(section)
                WasmComponentSection.SECTION_ID -> {
                    assertIs<WasmComponentSection>(section)
                    assertNull(with(section) { buffer.parseNestedComponent() })
                }
            }
        }
    }

    @Test
    fun `parse rejects oversized and overlong section size encodings`() {
        val header = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        listOf(
            byteArrayOf(0x08, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10),
            byteArrayOf(0x08, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x00),
        ).forEach { section ->
            val result = assertIs<WasmParseSuccess>(WasmParser().parse((header + section).toCommonByteBuffer()))

            assertEquals(listOf(8), result.sectionScanFailures.map { it.byteOffset })
            assertTrue(assertIs<WasmModule>(result.wasmModule).sections.isEmpty())
        }
    }

    @Test
    fun `parse rejects a section whose declared end is not representable`() {
        val header = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        val sectionWithIntMaxDeclaredSize = byteArrayOf(
            WasmSectionTypes.SECTION_ID,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07,
        )

        val result = assertIs<WasmParseSuccess>(
            WasmParser().parse((header + sectionWithIntMaxDeclaredSize).toCommonByteBuffer())
        )

        assertEquals(listOf(8), result.sectionScanFailures.map { it.byteOffset })
        assertTrue(assertIs<WasmModule>(result.wasmModule).sections.isEmpty())
    }

    @Test
    fun `custom section name cannot cross into the following section`() {
        val result = assertIs<WasmParseSuccess>(
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x03,
                0x01, 0x01, 0x00,
            ).toCommonByteBuffer().let { WasmParser().parse(it) }
        )
        val module = assertIs<WasmModule>(result.wasmModule)

        assertEquals(listOf(8), result.sectionScanFailures.map { it.byteOffset })
        assertTrue(module.customSections.isEmpty())
        assertEquals(2, module.sections.size)
        assertTrue(module.typesSection != null)
    }

    @Test
    fun `type parser preserves decoded prefix when a later entry is malformed`() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x05, // type section, five-byte payload
            0x02, // two entries
            0x60, 0x00, 0x00, // first entry: () -> ()
            0x7f, // unsupported type discriminant
        )
        val buffer = bytes.toCommonByteBuffer()
        val module = assertIs<WasmModule>(assertIs<WasmParseSuccess>(WasmParser().parse(buffer)).wasmModule)

        val (types, failedOffset) = with(module.typesSection!!) { buffer.parseTypesPartial() }

        assertEquals(1, types.size)
        assertEquals(14, failedOffset)
    }

    @Test
    fun `truncated type payload that decodes its vector remains a scan failure`() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x07, 0x01,
            0x60, 0x00, 0x00,
        )
        val buffer = bytes.toCommonByteBuffer()
        val result = assertIs<WasmParseSuccess>(WasmParser().parse(buffer))
        val typesSection = requireNotNull(assertIs<WasmModule>(result.wasmModule).typesSection)

        assertEquals(1, with(typesSection) { buffer.parseTypes().size })
        assertEquals(null, with(typesSection) { buffer.parseTypesPartial().second })
        assertEquals(listOf(8), result.sectionScanFailures.map { it.byteOffset })
    }

    /**
     * A code entry whose body is just `[locals=0, else]` with no matching terminator
     * and nothing left in the buffer afterward. `else` (0x05) recurses to parse its
     * nested body, which normally ends by consuming the shared `end` (0x0B) that also
     * terminates the enclosing `if`; the `else` handler then rewinds one byte to give
     * that shared terminator back to the caller.
     *
     * Before the fix: when input is truncated right after `else` (no terminator to
     * find), the nested body parse returns immediately having consumed nothing, and
     * the unconditional rewind lands back on the very same `else` byte -- the loop
     * re-reads it and appends a new [Flow.Else] forever (H2). After the fix, the
     * rewind only happens when the nested body actually advanced, so parsing
     * terminates with a single, bounded opcode list.
     */
    @Test
    fun `consumeCodeEntry terminates on body ending in bare else with no terminator`() {
        val bytes = byteArrayOf(
            0x02, // funSize = 2
            0x00, // locals count = 0
            0x05, // else, with nothing left in the buffer
        )
        val buffer = bytes.toCommonByteBuffer()

        val record = buffer.consumeCodeEntry()

        assertEquals(1, record.opCodes.size, "opcode list must stay bounded, not grow forever")
        val elseOp = assertIs<Flow.Else>(record.opCodes.single())
        assertTrue(elseOp.value.isEmpty(), "truncated else has no nested body")
        assertEquals(3, buffer.position(), "cursor must land at end of buffer, not loop")
    }

    /** Same trigger as above, exercised directly at the [parseOpCodes] level. */
    @Test
    fun `parseOpCodes terminates on bare else with no terminator`() {
        val buffer = byteArrayOf(0x05).toCommonByteBuffer()

        val ops = buffer.parseOpCodes()

        assertEquals(1, ops.size, "opcode list must stay bounded, not grow forever")
        assertIs<Flow.Else>(ops.single())
    }

    /**
     * `consumeString`'s declared name length is attacker-controlled. A length of
     * `0x7FFFFFFF` (encoded as the 5-byte ULEB128 `FF FF FF FF 07`) with nothing left
     * in the buffer must fail fast instead of attempting a ~2 GiB `ByteArray`
     * allocation (H4).
     */
    @Test
    fun `consumeString rejects a length exceeding the remaining buffer`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07)
        val buffer = bytes.toCommonByteBuffer()

        assertFailsWith<WasmParseException> { buffer.consumeString() }
    }

    /** A string whose declared length exactly matches the remaining bytes must still parse. */
    @Test
    fun `consumeString accepts a length exactly filling the remaining buffer`() {
        val bytes = byteArrayOf(0x03, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())
        val buffer = bytes.toCommonByteBuffer()

        assertEquals("abc", buffer.consumeString())
    }

    @Test
    fun `bounded u32 accepts UInt max and rejects a sixth byte`() {
        val max = byteArrayOf(
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f,
        ).toCommonByteBuffer()
        assertEquals(UInt.MAX_VALUE, max.consumeUnsignedUInt())

        val overlong = byteArrayOf(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x00,
        ).toCommonByteBuffer()
        assertFailsWith<WasmParseException> { overlong.consumeUnsignedUInt() }
        assertEquals(5, overlong.position())
    }

    @Test
    fun `component structural indices reject a sixth u32 byte`() {
        val overlongZero = byteArrayOf(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x00,
        )

        val coreAlias = (byteArrayOf(0x01) + overlongZero + byteArrayOf(0x00)).toCommonByteBuffer()
        assertFailsWith<WasmParseException> { Alias.run { coreAlias.aliasCoreTarget() } }

        val coreSortIndex = (byteArrayOf(0x00) + overlongZero).toCommonByteBuffer()
        assertFailsWith<WasmParseException> {
            ComponentCoreInstance.run { coreSortIndex.componentCoreSortIndex() }
        }

        val instantiateArgs = (byteArrayOf(0x01, 0x00, 0x00) + overlongZero).toCommonByteBuffer()
        assertFailsWith<WasmParseException> {
            ComponentCoreInstance.run { instantiateArgs.consumeInstantiateArgs() }
        }

        val instanceContent = byteArrayOf(0x01, 0x00) + overlongZero + byteArrayOf(0x00)
        val instanceSection = WasmInstanceSection(
            sectionSize = instanceContent.size,
            sectionOffset = 0,
            contentSize = instanceContent.size,
            contentOffset = 0,
        )
        val instanceBuffer = instanceContent.toCommonByteBuffer()
        assertFailsWith<WasmParseException> { with(instanceSection) { instanceBuffer.parseInstances() } }
    }

    /**
     * `consumeArray`'s declared vector count is attacker-controlled. A count of
     * `0x7FFFFFFF` with nothing left in the buffer must fail fast instead of driving
     * billions of `repeat` iterations (H4).
     */
    @Test
    fun `consumeArray rejects a count exceeding the remaining buffer`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07)
        val buffer = bytes.toCommonByteBuffer()

        assertFailsWith<WasmParseException> { buffer.consumeArray { } }
    }

    /** A vector of N one-byte elements where N equals the remaining bytes must still parse. */
    @Test
    fun `consumeArray accepts a count exactly filling the remaining buffer`() {
        val bytes = byteArrayOf(0x03, 0x01, 0x01, 0x01)
        val buffer = bytes.toCommonByteBuffer()

        var visited = 0
        val decoded = mutableListOf<Byte>()
        buffer.consumeArray {
            decoded.add(get())
            visited++
        }

        assertEquals(3, visited)
        assertEquals(listOf(0x01.toByte(), 0x01.toByte(), 0x01.toByte()), decoded)
        assertEquals(bytes.size, buffer.position())
    }

    @Test
    fun `consumeArray respects an explicit section boundary`() {
        val buffer = byteArrayOf(0x01, 0x00, 0x01).toCommonByteBuffer()
        val consumed = mutableListOf<Byte>()

        assertFailsWith<WasmParseException> {
            buffer.consumeArray(2) {
                consumed.add(get())
                consumed.add(get())
            }
        }

        assertEquals(listOf(0x00.toByte()), consumed)
        assertEquals(2, buffer.position())
        assertEquals(0x01.toByte(), buffer[2])
    }

    @Test
    fun `consumeArray translates a bounded bulk-read failure`() {
        val buffer = byteArrayOf(0x01, 0x00, 0x01).toCommonByteBuffer()

        assertFailsWith<WasmParseException> {
            buffer.consumeArray(2) {
                get(ByteArray(2), 0, 2)
            }
        }

        assertEquals(1, buffer.position())
        assertEquals(0x01.toByte(), buffer[2])
    }

    @Test
    fun `consumeArrayPartial retains prefix and entry failure offset`() {
        val buffer = byteArrayOf(0x02, 0x01, 0x80.toByte()).toCommonByteBuffer()

        val result = buffer.consumeArrayPartial(3) { getULEB128().toInt() }

        assertEquals(listOf(1), result.values)
        assertEquals(2, result.failureOffset)
    }

    @Test
    fun `consumeArrayPartial rejects an entry crossing its section boundary`() {
        val buffer = byteArrayOf(0x02, 0x01, 0x80.toByte(), 0x00).toCommonByteBuffer()

        val result = buffer.consumeArrayPartial(3) { getULEB128().toInt() }

        assertEquals(listOf(1), result.values)
        assertEquals(2, result.failureOffset)
    }

    @Test
    fun `consumeArrayPartial reports a complete vector at its boundary as clean`() {
        val buffer = byteArrayOf(0x01, 0x2a).toCommonByteBuffer()

        val result = buffer.consumeArrayPartial(2) { get().toInt() }

        assertEquals(listOf(0x2a), result.values)
        assertEquals(null, result.failureOffset)
    }

    @Test
    fun `consumeArrayPartial reports an excessive count at its start`() {
        val buffer = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f).toCommonByteBuffer()

        val result = buffer.consumeArrayPartial(5) { get() }

        assertTrue(result.values.isEmpty())
        assertEquals(0, result.failureOffset)
    }
}
