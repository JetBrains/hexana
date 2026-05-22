package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.document.io.toCommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end smoke tests for the WASM format adapter against a minimal,
 * hand-rolled WASM binary. Validates the seam from [WasmBinaryProvider] →
 * chunk tree → renderer. Larger fixture-based tests live alongside the
 * `wasmParser` module and exercise opcode coverage.
 */
class WasmChunkTreeIntegrationTest {

    /**
     * Smallest valid module:
     *
     * ```
     * \0asm 0x01 0x00 0x00 0x00          ; magic + version 1
     * 01 04 01 60 00 00                  ; type[0] = (func)
     * 03 02 01 00                        ; function[0] uses type 0
     * 0a 04 01 02 00 0b                  ; code[0] = no locals + end
     * ```
     */
    private val minimalWasm: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x04, 0x01, 0x02, 0x00, 0x0B,
    )

    /**
     * Module with an extra Memory section inserted before Code.
     * Memory section `05 04 01 01 00 01` encodes one entry: `(memory 0 1)`
     * — limits type 0x01 (fixed min+max), min=0, max=1.
     *
     * ```
     * \0asm 0x01 0x00 0x00 0x00          ; magic + version 1
     * 01 04 01 60 00 00                  ; type[0] = (func)
     * 03 02 01 00                        ; function[0] uses type 0
     * 05 04 01 01 00 01                  ; memory[0] = (memory 0 1)
     * 0a 04 01 02 00 0b                  ; code[0] = no locals + end
     * ```
     */
    private val minimalWasmWithMemory: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x04, 0x01, 0x01, 0x00, 0x01,
        0x0A, 0x04, 0x01, 0x02, 0x00, 0x0B,
    )

    private fun parsed(): WasmParsedBinary {
        val snapshot = binaryDocumentOf(minimalWasm.toCommonByteBuffer()).snapshot()
        val provider = WasmBinaryProvider(snapshot.toCommonByteBuffer())
        return WasmParsedBinary.fromProvider(provider, snapshot)
    }

    private fun parsedWithMemory(): WasmParsedBinary {
        val snapshot = binaryDocumentOf(minimalWasmWithMemory.toCommonByteBuffer()).snapshot()
        val provider = WasmBinaryProvider(snapshot.toCommonByteBuffer())
        return WasmParsedBinary.fromProvider(provider, snapshot)
    }

    @Test
    fun chunk_tree_has_header_and_three_section_headers() {
        val tree = buildWasmChunkTree(parsed())
        val sectionHeaders = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.SectionHeader>()
            .map { it.kind }
        // Module header + Type + Function + Code (in binary order).
        assertEquals(
            listOf(
                WasmSectionKinds.HEADER,
                WasmSectionKinds.TYPE,
                WasmSectionKinds.FUNCTION,
                WasmSectionKinds.CODE,
            ),
            sectionHeaders,
        )
    }

    @Test
    fun function_section_emits_one_element_per_entry() {
        val tree = buildWasmChunkTree(parsed())
        val funcElements = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .filter { it.kind == WasmSectionKinds.FUNCTION }
        // The test fixture declares one function; the Function section
        // must surface it as a single per-entry chunk so the user can scroll
        // to and Cmd+B from it.
        assertEquals(1, funcElements.size, "expected one Function-section element, got $funcElements")
        val fn = funcElements.single()
        assertEquals(0, fn.indexInSection)
        assertTrue(fn.byteRange.first > 0L, "function entry's byteRange must be inside the section")
    }

    @Test
    fun code_section_emits_one_element_per_function() {
        val tree = buildWasmChunkTree(parsed())
        val codeElements = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .filter { it.kind == WasmSectionKinds.CODE }
        assertEquals(1, codeElements.size)
        val fn = codeElements.single()
        assertEquals(0, fn.indexInSection)
        assertTrue(fn.byteRange.first > 0L)
    }

    @Test
    fun chunk_containing_byte_finds_section_for_known_offset() {
        val tree = buildWasmChunkTree(parsed())
        // The first byte of the binary is in the module header chunk.
        val headerIdx = tree.chunkContainingByte(0L)
        assertNotNull(headerIdx)
        val header = tree.chunkAt(headerIdx)
        assertEquals(WasmSectionKinds.HEADER, header.kind)
    }

    @Test
    fun chunk_containing_byte_returns_null_for_out_of_range() {
        val tree = buildWasmChunkTree(parsed())
        assertNull(tree.chunkContainingByte(minimalWasm.size.toLong() + 100L))
    }

    @Test
    fun symbol_resolver_returns_null_for_unnamed_function() {
        val parsed = parsed()
        // Minimal binary has no name section, no exports, no DWARF — every
        // namespace returns null and the UI applies its own fallback.
        assertNull(parsed.symbolResolver.resolve(SymbolKind.FUNCTION, 0))
    }

    @Test
    fun renderer_produces_function_header_for_code_chunk() {
        val parsed = parsed()
        val tree = buildWasmChunkTree(parsed)
        val renderer = WasmHexanaWatRenderer(parsed)
        val codeChunk = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .first { it.kind == WasmSectionKinds.CODE }

        val tokens = renderer.render(parsed.snapshot, codeChunk).tokens
        val text = tokens.joinToString("") { it.text }

        assertTrue("(func " in text, "expected '(func ' in:\n$text")
        assertTrue("\$func" in text, "expected fallback '\$func<idx>' in:\n$text")
        assertTrue("funcidx" in text, "expected 'funcidx' annotation in:\n$text")
    }

    @Test
    fun renderer_emits_offset_gutter_and_hex_bytes_for_function_close() {
        val parsed = parsed()
        val tree = buildWasmChunkTree(parsed)
        val renderer = WasmHexanaWatRenderer(parsed)
        val codeChunk = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .first { it.kind == WasmSectionKinds.CODE }

        val text = renderer.render(parsed.snapshot, codeChunk).tokens.joinToString("") { it.text }

        // The function's closing `end` byte (0x0B) should appear in the hex
        // bytes column on the closing line. Format: "0xXXXXXXXX  0B  ...  )"
        assertTrue("0B" in text, "expected '0B' (end byte) in rendered output:\n$text")
        assertTrue("0x" in text, "expected offset gutter (e.g. '0x00000017') in:\n$text")
    }

    // -------------------------------------------------------------------------
    // Unified column-layout tests (offset gutter + hex + WAT|label)
    // -------------------------------------------------------------------------

    @Test
    fun renderer_type_element_contains_type_keyword_identifier_and_functype_sentinel_hex() {
        val parsed = parsed()
        val tree = buildWasmChunkTree(parsed)
        val renderer = WasmHexanaWatRenderer(parsed)
        val typeChunk = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .first { it.kind == WasmSectionKinds.TYPE }

        val text = renderer.render(parsed.snapshot, typeChunk).tokens.joinToString("") { it.text }

        assertTrue("(type" in text, "expected '(type' keyword in:\n$text")
        assertTrue("\$type0" in text, "expected '\$type0' identifier in:\n$text")
        assertTrue("(func)" in text, "expected '(func)' inner functype in:\n$text")
        // 0x60 is the functype sentinel byte — must appear in the hex preview column.
        assertTrue("60" in text, "expected '60' (functype sentinel 0x60) in hex preview:\n$text")
    }

    @Test
    fun renderer_every_element_chunk_rendered_text_starts_with_offset_gutter() {
        val parsed = parsed()
        val tree = buildWasmChunkTree(parsed)
        val renderer = WasmHexanaWatRenderer(parsed)
        val elements = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()

        assertTrue(elements.isNotEmpty(), "expected at least one element chunk in the tree")
        for (chunk in elements) {
            val text = renderer.render(parsed.snapshot, chunk).tokens.joinToString("") { it.text }
            val firstToken = text.trimStart('\n')
            assertTrue(
                firstToken.startsWith("0x"),
                "element chunk kind=${chunk.kind} idx=${chunk.indexInSection}: " +
                    "expected rendered text to start with '0x' offset gutter, got:\n$text",
            )
        }
    }

    @Test
    fun renderer_code_element_function_header_line_starts_with_offset_gutter() {
        val parsed = parsed()
        val tree = buildWasmChunkTree(parsed)
        val renderer = WasmHexanaWatRenderer(parsed)
        val codeChunk = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .first { it.kind == WasmSectionKinds.CODE }

        val text = renderer.render(parsed.snapshot, codeChunk).tokens.joinToString("") { it.text }
        // The first line is the function header produced by emitStructuralRowPrefix.
        val firstLine = text.substringBefore('\n')
        assertTrue(
            firstLine.trimStart().startsWith("0x"),
            "expected function header line to start with '0x' offset gutter, got:\n$firstLine",
        )
    }

    @Test
    fun renderer_memory_element_contains_memory_keyword_identifier_and_min_limit() {
        val parsed = parsedWithMemory()
        val tree = buildWasmChunkTree(parsed)
        val renderer = WasmHexanaWatRenderer(parsed)
        val memChunks = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .filter { it.kind == WasmSectionKinds.MEMORY }

        assertEquals(1, memChunks.size, "expected exactly one MEMORY element chunk")
        val text = renderer.render(parsed.snapshot, memChunks.single()).tokens.joinToString("") { it.text }

        assertTrue("(memory" in text, "expected '(memory' keyword in:\n$text")
        assertTrue("\$mem0" in text, "expected '\$mem0' identifier in:\n$text")
        // min limit is 0 — must appear as the first limit number after the identifier.
        assertTrue(" 0 " in text || text.endsWith(" 0)") || " 0)" in text,
            "expected min limit '0' in memory declaration:\n$text")
    }
}
