package org.jetbrains.hexana.providers.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.io.toCommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider

/**
 * Pins the TYPE-section annotation rows and gcTypeIdx plumbing added in Layer 1.
 *
 * Fixture: typegraph.wasm (251 bytes, inline). Type index layout:
 *   rec group 0 (0..2): $Base=STRUCT, $Mid=STRUCT sub $Base, $BaseArr=ARRAY
 *   rec group 1 (3..4): $Leaf=STRUCT sub $Mid, $Other=STRUCT
 *   non-rec (5..8):     $Sig=FUNC, $SubArr=ARRAY sub $BaseArr, implicit FUNC, implicit FUNC
 *
 * GC opcodes used in fn_gc (funcIdx 0):
 *   struct.new_default $Base(0), ref.test (ref $Mid)(1), ref.cast_null (ref null $Mid)(1),
 *   struct.get $Mid 0(1), array.new_default $BaseArr(2), call_ref $Sig(5)
 */
class WasmTypeAnnotationRowTest {

    // typegraph.wasm — 251 bytes, embedded inline to avoid test-resource discovery issues.
    // Bytes above 0x7F need explicit .toByte() casts since Kotlin byteArrayOf takes Byte, not Int.
    private val typegraphWasm: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x44, 0x06, 0x4E, 0x03, 0x50, 0x00, 0x5F,
        0x01, 0x7F, 0x00, 0x50, 0x01, 0x00, 0x5F, 0x02,
        0x7F, 0x00, 0x63, 0x02, 0x00, 0x50, 0x00, 0x5E,
        0x7F, 0x01, 0x4E, 0x02, 0x4F, 0x01, 0x01, 0x5F,
        0x03, 0x7F, 0x00, 0x63, 0x02, 0x00, 0x7F, 0x00,
        0x5F, 0x02, 0x63, 0x02, 0x00, 0x63, 0x00, 0x00,
        0x60, 0x01, 0x63, 0x00, 0x01, 0x7F, 0x50, 0x01,
        0x02, 0x5E, 0x7F, 0x01, 0x60, 0x01, 0x63, 0x05,
        0x01, 0x7F, 0x60, 0x00, 0x01, 0x7F, 0x03, 0x03,
        0x02, 0x07, 0x08, 0x04, 0x04, 0x01, 0x70, 0x00,
        0x01, 0x07, 0x14, 0x02, 0x05, 0x66, 0x6E, 0x5F,
        0x67, 0x63, 0x00, 0x00, 0x08, 0x66, 0x6E, 0x5F,
        0x6F, 0x74, 0x68, 0x65, 0x72, 0x00, 0x01, 0x09,
        0x07, 0x01, 0x00, 0x41, 0x00, 0x0B, 0x01, 0x01,
        0x0A, 0x2B, 0x02, 0x24, 0x00, 0x41, 0x04, 0xFB.toByte(),
        0x07, 0x02, 0x1A, 0xD0.toByte(), 0x00, 0xFB.toByte(), 0x14, 0x01,
        0x1A, 0xD0.toByte(), 0x00, 0xFB.toByte(), 0x17, 0x01, 0x1A, 0xD0.toByte(),
        0x01, 0xFB.toByte(), 0x02, 0x01, 0x00, 0x1A, 0xD0.toByte(), 0x00,
        0x20, 0x00, 0x14, 0x05, 0x1A, 0x41, 0x00, 0x0B,
        0x04, 0x00, 0x41, 0x2A, 0x0B, 0x00, 0x54, 0x04,
        0x6E, 0x61, 0x6D, 0x65, 0x01, 0x12, 0x02, 0x00,
        0x05, 0x66, 0x6E, 0x5F, 0x67, 0x63, 0x01, 0x08,
        0x66, 0x6E, 0x5F, 0x6F, 0x74, 0x68, 0x65, 0x72,
        0x02, 0x08, 0x01, 0x00, 0x01, 0x00, 0x03, 0x73,
        0x69, 0x67, 0x04, 0x2F, 0x07, 0x00, 0x04, 0x42,
        0x61, 0x73, 0x65, 0x01, 0x03, 0x4D, 0x69, 0x64,
        0x02, 0x07, 0x42, 0x61, 0x73, 0x65, 0x41, 0x72,
        0x72, 0x03, 0x04, 0x4C, 0x65, 0x61, 0x66, 0x04,
        0x05, 0x4F, 0x74, 0x68, 0x65, 0x72, 0x05, 0x03,
        0x53, 0x69, 0x67, 0x06, 0x06, 0x53, 0x75, 0x62,
        0x41, 0x72, 0x72,
    )

    private fun setup(): Triple<WasmParsedBinary, List<BinaryChunk.Element>, WasmHexanaWatRenderer> {
        val snapshot = binaryDocumentOf(typegraphWasm.toCommonByteBuffer()).snapshot()
        val provider = WasmBinaryProvider(snapshot.toCommonByteBuffer())
        val parsed = WasmParsedBinary.fromProvider(provider, snapshot)
        val tree = buildWasmChunkTree(parsed)
        val elements = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
        val renderer = WasmHexanaWatRenderer(parsed)
        return Triple(parsed, elements, renderer)
    }

    private fun typeChunks(elements: List<BinaryChunk.Element>): List<BinaryChunk.Element> =
        elements.filter { it.kind == WasmSectionKinds.TYPE }.sortedBy { it.indexInSection }

    private fun codeChunks(elements: List<BinaryChunk.Element>): List<BinaryChunk.Element> =
        elements.filter { it.kind == WasmSectionKinds.CODE }.sortedBy { it.indexInSection }

    // --- TYPE-section tests ---

    @Test
    fun type_rows_are_plain_when_flag_is_off() {
        val (parsed, elements, renderer) = setup()
        val snapshot = parsed.snapshot
        val chunks = typeChunks(elements)

        // 9 type entries total (6 explicit + 3 implicit func types from wasm-tools)
        assertEquals(9, chunks.size, "expected 9 type chunks, got ${chunks.size}")
        for (chunk in chunks) {
            val rows = renderer.renderRows(snapshot, chunk)
            assertEquals(
                0,
                rows.count { it.kind is RowKind.TypeAnnotation },
                "type chunk ${chunk.indexInSection}: no TypeAnnotation rows when flag is off",
            )
        }
    }

    @Test
    fun type_rows_each_produce_two_rows_when_flag_is_on() {
        val (parsed, elements, renderer) = setup()
        renderer.inlineTypeAnnotations = true
        val snapshot = parsed.snapshot
        val chunks = typeChunks(elements)

        assertEquals(9, chunks.size, "expected 9 type chunks")
        for (chunk in chunks) {
            val rows = renderer.renderRows(snapshot, chunk)
            assertEquals(
                2,
                rows.size,
                "type chunk ${chunk.indexInSection}: expected 2 rows (def + annotation), got ${rows.size}",
            )
            val annKind = rows[1].kind
            assertIs<RowKind.TypeAnnotation>(annKind, "second row must be TypeAnnotation for chunk ${chunk.indexInSection}")
            assertEquals(chunk.indexInSection, annKind.typeIdx, "TypeAnnotation.typeIdx must match chunk index")
        }
    }

    @Test
    fun type_annotation_rows_carry_correct_typeIdx() {
        val (parsed, elements, renderer) = setup()
        renderer.inlineTypeAnnotations = true
        val snapshot = parsed.snapshot
        val chunks = typeChunks(elements)

        // Spot-check indices 0..4 (the explicitly declared GC types)
        for (expectedIdx in 0..4) {
            val chunk = chunks.first { it.indexInSection == expectedIdx }
            val rows = renderer.renderRows(snapshot, chunk)
            val kind = rows[1].kind as RowKind.TypeAnnotation
            assertEquals(expectedIdx, kind.typeIdx)
        }

        // $Base (typeIdx 0): 1 subtype ($Mid), 0 refFuncs, rec group #0
        val baseChunk = chunks.first { it.indexInSection == 0 }
        val baseAnn = renderer.renderRows(snapshot, baseChunk)[1]
        val baseText = baseAnn.tokens[1].text
        assertTrue(baseText.contains("subtypes: 1"), "expected 'subtypes: 1' in \$Base annotation: $baseText")
        assertTrue(baseText.contains("rec group #0"), "expected 'rec group #0' in \$Base annotation: $baseText")

        // $Mid (typeIdx 1): 1 subtype ($Leaf), 1 refFunc ($fn_gc), rec group #0
        val midChunk = chunks.first { it.indexInSection == 1 }
        val midAnn = renderer.renderRows(snapshot, midChunk)[1]
        val midText = midAnn.tokens[1].text
        assertTrue(midText.contains("subtypes: 1"), "expected 'subtypes: 1' in \$Mid annotation: $midText")
        assertTrue(midText.contains("rec group #0"), "expected 'rec group #0' in \$Mid annotation: $midText")
    }

    @Test
    fun def_row_for_subtype_carries_typeLinks() {
        val (parsed, elements, renderer) = setup()
        renderer.inlineTypeAnnotations = true
        val snapshot = parsed.snapshot
        val chunks = typeChunks(elements)

        // $Mid (typeIdx 1) has supertype $Base (typeIdx 0) — must have exactly one TypeTokenLink
        val midChunk = chunks.first { it.indexInSection == 1 }
        val midRows = renderer.renderRows(snapshot, midChunk)
        val defRow = midRows[0]
        assertTrue(defRow.typeLinks.isNotEmpty(), "\$Mid def row must have typeLinks for its supertype \$Base")
        assertTrue(defRow.typeLinks.any { it.typeIdx == 0 }, "typeLinks must reference typeIdx 0 (\$Base)")

        // $Leaf (typeIdx 3) has supertype $Mid (typeIdx 1) and one ref field (ref null $BaseArr, typeIdx 2).
        // Expect exactly 2 typeLinks: one for the supertype ref and one for the ref-typed field.
        val leafChunk = chunks.first { it.indexInSection == 3 }
        val leafRows = renderer.renderRows(snapshot, leafChunk)
        val leafDef = leafRows[0]
        assertEquals(2, leafDef.typeLinks.size, "\$Leaf def row must have exactly 2 typeLinks (supertype + ref field)")
        assertTrue(leafDef.typeLinks.any { it.typeIdx == 1 }, "typeLinks must reference typeIdx 1 (\$Mid) as supertype")
        assertTrue(leafDef.typeLinks.any { it.typeIdx == 2 }, "typeLinks must reference typeIdx 2 (\$BaseArr) as ref field")
    }

    @Test
    fun def_row_for_non_subtype_has_no_supertype_links() {
        val (parsed, elements, renderer) = setup()
        renderer.inlineTypeAnnotations = true
        val snapshot = parsed.snapshot
        val chunks = typeChunks(elements)

        // $Base (typeIdx 0) has no supertype
        val baseChunk = chunks.first { it.indexInSection == 0 }
        val baseRows = renderer.renderRows(snapshot, baseChunk)
        // No supertype links, but $Base has no ref fields either — typeLinks empty
        val supertypeLinks = baseRows[0].typeLinks
        assertTrue(supertypeLinks.none { it.typeIdx == 0 }, "\$Base must not link to itself")
    }

    // --- CODE-section tests ---

    @Test
    fun gc_opcode_rows_carry_gcTypeIdx() {
        val (parsed, elements, renderer) = setup()
        val snapshot = parsed.snapshot

        // fn_gc is funcIdx 0 (no imports). Its body uses GC opcodes referencing:
        //   struct.new_default typeIdx=2 ($BaseArr),
        //   ref.test (ref $Mid) typeIdx=1, ref.cast_null (ref null $Mid) typeIdx=1,
        //   struct.get $Mid field 0 typeIdx=1,
        //   array.new_default $BaseArr typeIdx=2,
        //   call_ref $Sig typeIdx=5.
        // Distinct set: {1, 2, 5}.
        val fnGcChunk = codeChunks(elements).first { it.indexInSection == 0 }
        val rows = renderer.renderRows(snapshot, fnGcChunk)
        val gcRows = rows.filter { it.gcTypeIdx != null }

        assertTrue(gcRows.isNotEmpty(), "fn_gc must have at least one row with gcTypeIdx set")
        val gcTypeIdxValues = gcRows.map { it.gcTypeIdx!! }.toSet()
        // Exact set of referenced type indices in fn_gc's body
        assertEquals(
            setOf(1, 2, 5),
            gcTypeIdxValues,
            "fn_gc gcTypeIdx set must be exactly {1, 2, 5}, got $gcTypeIdxValues",
        )
    }

    @Test
    fun non_gc_rows_in_code_have_null_gcTypeIdx() {
        val (parsed, elements, renderer) = setup()
        val snapshot = parsed.snapshot

        // fn_other (funcIdx 1) only uses i32.const and end — no GC opcodes
        val fnOtherChunk = codeChunks(elements).first { it.indexInSection == 1 }
        val rows = renderer.renderRows(snapshot, fnOtherChunk)
        val gcRows = rows.filter { it.gcTypeIdx != null }
        assertEquals(0, gcRows.size, "fn_other has no GC opcodes; expected 0 gcTypeIdx rows, got ${gcRows.size}")
    }
}
