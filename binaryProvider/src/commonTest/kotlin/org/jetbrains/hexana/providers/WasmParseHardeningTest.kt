package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.sections.ParseProblemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hardening tests for [WasmBinaryProvider] on malformed WASM binaries.
 *
 * Each test documents which assertion fails without the corresponding fix,
 * proving the test is not vacuous.
 */
class WasmParseHardeningTest {

    // -------------------------------------------------------------------
    // Helper: WASM magic + version 1 header (8 bytes)
    // -------------------------------------------------------------------
    private val HEADER = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

    // -------------------------------------------------------------------
    // Test 1: truncated section header (custom.3.wasm style)
    //
    // Binary: magic+version (8 bytes) + one section-id byte (0x00 = custom)
    // with zero bytes remaining for the LEB128 size.
    //
    // Pre-fix: construction throws BufferUnderflowException (escapes constructor).
    // Post-fix: construction succeeds; parseProblems contains exactly 1
    //   SECTION_TRUNCATED problem at byteOffset 0x08.
    // -------------------------------------------------------------------
    private val truncatedSectionHeader: ByteArray =
        HEADER + byteArrayOf(0x00) // section-id only, no size byte

    /** A complete type-section header whose empty payload omits its required vector count. */
    private val missingTypeVectorCount: ByteArray =
        HEADER + byteArrayOf(0x01, 0x00)

    /** A complete type vector whose declared section payload has bytes missing at EOF. */
    private val truncatedTypePayloadAfterCompleteVector: ByteArray =
        HEADER + byteArrayOf(0x01, 0x07, 0x01, 0x60, 0x00, 0x00)

    @Test
    fun truncated_section_header_construction_succeeds() {
        // Pre-fix: this line throws. Post-fix: it completes.
        val provider = WasmBinaryProvider(truncatedSectionHeader.toCommonByteBuffer())
        // Verify it did not produce a module (no valid sections were decoded).
        // The primary assertion is that no exception escaped construction.
        assertTrue(provider.parseProblems.isNotEmpty() || provider.wasmModule == null,
            "malformed binary must either report a problem or yield no module")
    }

    @Test
    fun truncated_section_header_reports_section_truncated_problem() {
        val provider = WasmBinaryProvider(truncatedSectionHeader.toCommonByteBuffer())
        // Pre-fix: parseProblems is never reached (constructor throws).
        val problem = provider.parseProblems.singleOrNull {
            it.kind == ParseProblemKind.SECTION_TRUNCATED
        }
        assertNotNull(problem, "expected exactly one SECTION_TRUNCATED problem, got ${provider.parseProblems}")
        // The bad section starts at offset 8 (right after magic+version).
        assertEquals(0x08, problem.byteOffset, "SECTION_TRUNCATED must point at the section start byte")
    }

    @Test
    fun missing_type_vector_count_keeps_identified_module_and_reports_problem() {
        val provider = WasmBinaryProvider(missingTypeVectorCount.toCommonByteBuffer())

        assertNotNull(provider.wasmModule, "valid header and section header must still identify the module")
        val problem = provider.parseProblems.singleOrNull { it.kind == ParseProblemKind.UNKNOWN_TYPE_BYTE }
        assertNotNull(problem, "missing type vector count must remain observable as parse evidence")
        assertEquals(10, problem.byteOffset, "missing vector count begins at the type payload")
    }

    @Test
    fun truncated_type_payload_after_complete_vector_reports_section_truncated() {
        val provider = WasmBinaryProvider(truncatedTypePayloadAfterCompleteVector.toCommonByteBuffer())

        assertNotNull(provider.wasmModule, "the recognized type section must remain available")
        assertEquals(listOf(ParseProblemKind.SECTION_TRUNCATED), provider.parseProblems.map { it.kind })
        assertEquals(8, provider.parseProblems.single().byteOffset)
    }

    @Test
    fun overlong_u32_core_entry_fields_report_the_entry_as_corrupt() {
        val overlongZero = byteArrayOf(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x00,
        )
        val cases = listOf(
            Triple("function type index", byteArrayOf(0x03, 0x07, 0x01) + overlongZero, ParseProblemKind.SECTION_ENTRY_CORRUPT),
            Triple("import type index", byteArrayOf(0x02, 0x0a, 0x01, 0x00, 0x00, 0x00) + overlongZero, ParseProblemKind.IMPORT_ENTRY_CORRUPT),
            Triple("export index", byteArrayOf(0x07, 0x09, 0x01, 0x00, 0x00) + overlongZero, ParseProblemKind.EXPORT_ENTRY_CORRUPT),
        )

        for ((field, section, expectedKind) in cases) {
            val problem = WasmBinaryProvider((HEADER + section).toCommonByteBuffer()).parseProblems.single()
            assertEquals(expectedKind, problem.kind, field)
            assertEquals(11, problem.byteOffset, field)
        }
    }

    @Test
    fun overlong_u32_component_entries_report_the_entry_as_corrupt() {
        val componentHeader = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)
        val overlongZero = byteArrayOf(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x00,
        )
        val cases = listOf(
            Triple("type index", byteArrayOf(0x07, 0x08, 0x01, 0x69) + overlongZero, ParseProblemKind.SECTION_ENTRY_CORRUPT),
            Triple("alias index", byteArrayOf(0x06, 0x09, 0x01, 0x03, 0x02) + overlongZero, ParseProblemKind.SECTION_ENTRY_CORRUPT),
            Triple("import type index", byteArrayOf(0x0a, 0x0a, 0x01, 0x00, 0x00, 0x01) + overlongZero, ParseProblemKind.SECTION_ENTRY_CORRUPT),
            Triple("export index", byteArrayOf(0x0b, 0x0a, 0x01, 0x00, 0x00, 0x01) + overlongZero, ParseProblemKind.SECTION_ENTRY_CORRUPT),
            Triple("core instance module index", byteArrayOf(0x02, 0x09, 0x01, 0x00) + overlongZero + byteArrayOf(0x00), ParseProblemKind.CORE_INSTANCE_ENTRY_CORRUPT),
        )

        for ((field, section, expectedKind) in cases) {
            val problem = WasmComponentBinaryProvider((componentHeader + section).toCommonByteBuffer()).parseProblems.single()
            assertEquals(expectedKind, problem.kind, field)
            assertEquals(11, problem.byteOffset, field)
        }
    }

    // -------------------------------------------------------------------
    // Test 2: custom section with name-length > remaining bytes (custom.9 style)
    //
    // Binary: header + section-id 0x00 + size 0x05 (5 content bytes) +
    //   name-len LEB 0x73 (= 115) + 4 bytes of padding.
    // consumeString() rejects 115 > 4 remaining, throws WasmParseException.
    //
    // Pre-fix: the WasmParseException escapes the parse loop (unguarded
    //   coreResolver call) and bubbles to the constructor caller.
    // Post-fix: caught, recorded as SECTION_TRUNCATED at byteOffset 8;
    //   construction completes.
    // -------------------------------------------------------------------
    private val badCustomSectionName: ByteArray =
        HEADER + byteArrayOf(
            0x00,                       // section-id = custom
            0x05,                       // section content size = 5
            0x73,                       // name-len LEB = 115 (> 4 remaining)
            0x41, 0x42, 0x43, 0x44,    // 4 filler bytes
        )

    @Test
    fun bad_custom_section_name_construction_succeeds() {
        // Pre-fix: WasmParseException propagates out of parse(), which propagates
        // out of the `parseResult` field initializer in the constructor.
        val provider = WasmBinaryProvider(badCustomSectionName.toCommonByteBuffer())
        // Accessing parseProblems must not throw either.
        val problems = provider.parseProblems
        assertTrue(problems.isNotEmpty(), "bad custom-section name must produce at least one problem")
    }

    @Test
    fun bad_custom_section_name_reports_section_truncated_at_correct_offset() {
        val provider = WasmBinaryProvider(badCustomSectionName.toCommonByteBuffer())
        // Pre-fix: constructor throws, so this line is never reached.
        val problem = provider.parseProblems.singleOrNull {
            it.kind == ParseProblemKind.SECTION_TRUNCATED
        }
        assertNotNull(problem, "expected a SECTION_TRUNCATED problem, got ${provider.parseProblems}")
        assertEquals(0x08, problem.byteOffset, "section starts at offset 8")
    }

    // -------------------------------------------------------------------
    // Test 3: overlong LEB128 in code body (binary-leb128.43 style)
    //
    // A function body contains an 11-byte u32 LEB128 (all continuation bits
    // set). Pre-fix: parser reads all 11 bytes, drifts past the declared code
    // entry boundary, resumes on ghost bytes, and eventually throws
    // BufferUnderflowException out of some later parse call.
    // Post-fix: getULEB128 stops at 10 bytes; consumeCodeEntry's catch-all
    // degrades the entry to UnparsedOpCode; parseProblems reports
    // CODE_ENTRY_CORRUPT (no prefix decoded) — no uncaught exception.
    //
    // Module layout:
    //   type[0] = () -> ()
    //   function[0] uses type 0
    //   code[0]: size=14, no locals, i32.const 0 (opcode 0x41 0x00),
    //     i32.load memarg: align=0 (LEB 0x00), offset = 11-byte overlong LEB,
    //     end (0x0b)
    // The overlong offset LEB: 10 continuation bytes (0x80) + terminal 0x00 = 11 bytes total.
    // Body size: 1 (no-locals) + 2 (i32.const 0) + 1 (i32.load opcode) +
    //            1 (align) + 11 (overlong offset) + 1 (end) = 17 bytes.
    // Code section: 1 (count) + 1 (size LEB for 17) + 17 = 19 bytes.
    // -------------------------------------------------------------------
    private val overlongLeb128InCodeBody: ByteArray = byteArrayOf(
        // magic + version
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        // type section: type[0] = () -> ()
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        // function section: function[0] uses type 0
        0x03, 0x02, 0x01, 0x00,
        // code section (id=10, size=19)
        0x0A, 0x13,
          0x01,         // 1 entry
          0x11,         // entry size = 17
            0x00,       // no locals
            0x41, 0x00, // i32.const 0
            0x28,       // i32.load
            0x00,       // align = 0
            // 11-byte overlong u32 LEB128 (10 continuation bytes + terminal):
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0x80.toByte(), 0x80.toByte(), 0x00,
            0x0B,       // end
    )

    @Test
    fun overlong_leb128_in_code_body_does_not_throw() {
        // Pre-fix: either construction or a property access throws
        // BufferUnderflowException due to position drift past the code entry.
        val provider = WasmBinaryProvider(overlongLeb128InCodeBody.toCommonByteBuffer())
        // Force code records and parse problems — both must complete without throwing.
        val records = provider.codeRecords
        val problems = provider.parseProblems
        // Either the entry is corrupt (UnparsedOpCode) or code decoded fine.
        // The key guarantee is that no exception escapes.
        assertTrue(records.isNotEmpty() || problems.isNotEmpty(),
            "expected code records or problems, not a silent empty result")
    }

    @Test
    fun overlong_leb128_in_code_body_has_one_code_record_and_no_section_truncation() {
        val provider = WasmBinaryProvider(overlongLeb128InCodeBody.toCommonByteBuffer())
        // Pre-fix: accessing codeRecords / parseProblems throws BufferUnderflowException
        // because the LEB128 reader drifts 11 bytes and exits the code entry boundary.
        val records = provider.codeRecords
        val problems = provider.parseProblems
        // The module has exactly one code entry; it must be accessible without throw.
        assertEquals(1, records.size, "fixture has one function, must produce one code record")
        // No section-truncation problems: the headers are all well-formed.
        assertTrue(problems.none { it.kind == ParseProblemKind.SECTION_TRUNCATED },
            "well-formed section headers must not produce SECTION_TRUNCATED, got $problems")
    }

    // -------------------------------------------------------------------
    // Test 4: init-expr failure in global section
    //
    // A global section entry whose init-expression uses an opcode the parser
    // does not recognize (0xFF = not a valid init-expr opcode). The scan
    // throws before producing any global records.
    //
    // Pre-fix: WasmBinaryProvider.data / parseProblems (or the lazy that calls
    //   globalSection.scan()) throws an uncaught exception.
    // Post-fix: parseProblems catches the scan failure and emits an
    //   INIT_EXPR_PARSE_FAILURE problem; globals decoded before the bad entry
    //   may be empty (per-section degradation).
    //
    // Layout: type[0]=()→(), global[0]: type i32 mut, init = 0xFF (bad opcode)
    // Global section content: 1 (count) + 0x7F (i32) + 0x01 (mutable) + 0xFF (bad opcode)
    // -------------------------------------------------------------------
    private val globalSectionInitExprFailure: ByteArray = byteArrayOf(
        // magic + version
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        // type section (minimal, 1 func type () -> ())
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        // global section (id=6, size=4): 1 global, i32 mutable, bad init opcode
        0x06, 0x04,
          0x01,         // count = 1
          0x7F,         // value type = i32
          0x01,         // mutability = mutable
          0xFF.toByte(),// invalid init opcode (not i32.const / global.get / end)
    )

    @Test
    fun global_section_init_expr_failure_construction_succeeds() {
        // Pre-fix: construction or first property access throws.
        val provider = WasmBinaryProvider(globalSectionInitExprFailure.toCommonByteBuffer())
        // Must not throw; module may or may not be present.
        @Suppress("UNUSED_VARIABLE") val ignored = provider.wasmModule
    }

    @Test
    fun global_section_init_expr_failure_emits_init_expr_problem() {
        val provider = WasmBinaryProvider(globalSectionInitExprFailure.toCommonByteBuffer())
        // Pre-fix: parseProblems itself throws (uncaught from globalSection.scan()).
        val problems = provider.parseProblems
        val initProblem = problems.firstOrNull { it.kind == ParseProblemKind.INIT_EXPR_PARSE_FAILURE }
        assertNotNull(initProblem,
            "global section with bad init opcode must emit INIT_EXPR_PARSE_FAILURE, got $problems")
    }

    // -------------------------------------------------------------------
    // Test 5: clean module still reports zero problems
    //
    // Ensures guards do not introduce false positives.
    // -------------------------------------------------------------------
    private val minimalClean: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x04, 0x01, 0x02, 0x00, 0x0B,
    )

    @Test
    fun clean_module_still_reports_zero_hardening_problems() {
        val provider = WasmBinaryProvider(minimalClean.toCommonByteBuffer())
        val problems = provider.parseProblems
        assertTrue(problems.isEmpty(),
            "clean module must have no parse problems after hardening, got $problems")
    }

    // -------------------------------------------------------------------
    // Test 6: unknown import extern type (invalid/binary.66.wasm, byte-exact)
    //
    // Binary: header + import section id 0x02, size 0x04, count 1, empty
    //   module/name strings, extern-type byte 0x05 (only 0x00-0x04 exist).
    //
    // Pre-fix: importRecord() throws IllegalStateException "unknown import
    //   type: 5" (WasmSectionImport.kt error()) from every getImportRecords
    //   caller; parseProblems stays empty (silent truncation).
    // Post-fix: getImportRecords degrades to the decoded prefix and
    //   parseProblems reports one IMPORT_ENTRY_CORRUPT at the entry start.
    // -------------------------------------------------------------------
    private val unknownImportExternType: ByteArray =
        HEADER + byteArrayOf(
            0x02, 0x04,     // import section, content size 4
            0x01,           // count = 1
            0x00, 0x00,     // empty module name, empty field name
            0x05,           // extern-type byte 0x05: unknown
        )

    @Test
    fun unknown_import_extern_type_degrades_to_prefix() {
        val provider = WasmBinaryProvider(unknownImportExternType.toCommonByteBuffer())
        // Pre-fix: this access throws IllegalStateException("unknown import type: 5").
        assertEquals(0, provider.imports.size, "no import decodes before the corrupt entry")
    }

    @Test
    fun unknown_import_extern_type_reports_import_entry_corrupt() {
        val provider = WasmBinaryProvider(unknownImportExternType.toCommonByteBuffer())
        val problem = provider.parseProblems.singleOrNull()
        assertNotNull(problem, "expected exactly one problem, got ${provider.parseProblems}")
        // Pre-fix: parseProblems is empty (the guard swallowed the throw silently).
        assertEquals(ParseProblemKind.IMPORT_ENTRY_CORRUPT, problem.kind)
        // Entry starts right after the count byte: 8 header + 2 section header + 1 count.
        assertEquals(11, problem.byteOffset, "problem must point at the corrupt entry start")
    }

    @Test
    fun import_entries_before_the_corrupt_one_are_preserved() {
        // Two imports: a valid function import "a"."b" (type index 0), then the 0x05 entry.
        val bytes = HEADER + byteArrayOf(
            0x02, 0x0A,                 // import section, content size 10
            0x02,                       // count = 2
            0x01, 0x61, 0x01, 0x62,     // "a" "b"
            0x00, 0x00,                 // func import, type index 0
            0x00, 0x00,                 // empty module name, empty field name
            0x05,                       // extern-type byte 0x05: unknown
        )
        val provider = WasmBinaryProvider(bytes.toCommonByteBuffer())
        assertEquals(1, provider.imports.size, "the valid import before the corrupt entry must survive")
        assertEquals(1, provider.parseProblems.count { it.kind == ParseProblemKind.IMPORT_ENTRY_CORRUPT })
    }

    // -------------------------------------------------------------------
    // Test 7: truncated export entry (invalid/binary.89.wasm class)
    //
    // Binary: header + export section id 0x07, size 0x04, count 1, name "a",
    //   extern-index byte 0x00, then EOF where the index LEB128 should be.
    //
    // Pre-fix: scan() throws from getULEB128 underflow (guarded lazies made it
    //   silent); parseProblems stays empty.
    // Post-fix: scan degrades to prefix; one EXPORT_ENTRY_CORRUPT reported.
    // -------------------------------------------------------------------
    private val truncatedExportEntry: ByteArray =
        HEADER + byteArrayOf(
            0x07, 0x04,     // export section, content size 4
            0x01,           // count = 1
            0x01, 0x61,     // name "a"
            0x00,           // extern-index = func; index LEB128 missing (EOF)
        )

    @Test
    fun truncated_export_entry_reports_export_entry_corrupt() {
        val provider = WasmBinaryProvider(truncatedExportEntry.toCommonByteBuffer())
        assertEquals(0, provider.exports.size, "no export decodes from the truncated entry")
        val problem = provider.parseProblems.singleOrNull()
        assertNotNull(problem, "expected exactly one problem, got ${provider.parseProblems}")
        assertEquals(ParseProblemKind.EXPORT_ENTRY_CORRUPT, problem.kind)
        assertEquals(11, problem.byteOffset, "problem must point at the corrupt entry start")
    }

    // -------------------------------------------------------------------
    // Test 8: table section with an invalid reftype byte
    //
    // Binary: header + table section id=0x04, size=0x02, count=1,
    //   then 0xFF (not a valid reftype byte).
    //
    // Layout:
    //   offset 0-7:  magic + version
    //   offset 8:    section id 0x04 (table)
    //   offset 9:    content size 0x02
    //   offset 10:   count = 0x01
    //   offset 11:   0xFF (invalid reftype)
    //
    // Pre-fix: scanTable() throws on the invalid reftype; no problem is recorded.
    // Post-fix: scanTablePartial() catches the failure; one SECTION_ENTRY_CORRUPT
    //   reported with byteOffset == 11 (the entry start).
    // -------------------------------------------------------------------
    private val tableSectionInvalidReftype: ByteArray =
        HEADER + byteArrayOf(
            0x04, 0x02,     // table section, content size 2
            0x01,           // count = 1
            0xFF.toByte(),  // invalid reftype (not 0x6B/0x6C/0x6D/0x6E/0x6F/0x70/0x7B etc.)
        )

    @Test
    fun table_section_invalid_reftype_reports_section_entry_corrupt() {
        val provider = WasmBinaryProvider(tableSectionInvalidReftype.toCommonByteBuffer())
        // Pre-fix: parseProblems is empty (the throw escaped without being caught).
        val problem = provider.parseProblems.singleOrNull { it.kind == ParseProblemKind.SECTION_ENTRY_CORRUPT }
        assertNotNull(problem, "expected one SECTION_ENTRY_CORRUPT, got ${provider.parseProblems}")
        // Entry 0 starts at offset 11 (8 header + 1 id + 1 size_leb + 1 count).
        assertEquals(11, problem.byteOffset, "problem must point at the corrupt table entry start")
    }

    // -------------------------------------------------------------------
    // Test 9: memory section with truncated limits (flags byte only)
    //
    // Binary: header + memory section id=0x05, size=0x01, count=1, then EOF.
    // The limits() call tries to read the flags byte but the buffer is exhausted.
    //
    // Layout:
    //   offset 0-7:  magic + version
    //   offset 8:    section id 0x05 (memory)
    //   offset 9:    content size 0x01
    //   offset 10:   count = 0x01
    //   (EOF -- no flags byte for the limits record)
    //
    // Pre-fix: scan() throws on buffer underflow; no problem is recorded.
    // Post-fix: scanPartial() catches the failure; one SECTION_ENTRY_CORRUPT
    //   reported with byteOffset == 11 (the entry start, right after count byte).
    // -------------------------------------------------------------------
    private val memorySectionTruncatedLimits: ByteArray =
        HEADER + byteArrayOf(
            0x05, 0x01,     // memory section, content size 1
            0x01,           // count = 1 (no limits bytes follow)
        )

    @Test
    fun memory_section_truncated_limits_reports_section_entry_corrupt() {
        val provider = WasmBinaryProvider(memorySectionTruncatedLimits.toCommonByteBuffer())
        // Pre-fix: parseProblems is empty (the throw was unguarded).
        val problem = provider.parseProblems.singleOrNull { it.kind == ParseProblemKind.SECTION_ENTRY_CORRUPT }
        assertNotNull(problem, "expected one SECTION_ENTRY_CORRUPT, got ${provider.parseProblems}")
        // Entry 0 starts at offset 11 (8 header + 1 id + 1 size_leb + 1 count).
        assertEquals(11, problem.byteOffset, "problem must point at the corrupt memory entry start")
    }

    // -------------------------------------------------------------------
    // Test 10: function section truncated mid-vector (count=2, one typeidx present)
    //
    // Binary: header + type section (type[0] = () -> ()) + function section
    //   with count=2 but only one typeidx byte present before EOF.
    //
    // Layout:
    //   offset 0-7:  magic + version
    //   offset 8-13: type section (id=0x01, size=0x04, 1 func type () -> ())
    //   offset 14:   section id 0x03 (function)
    //   offset 15:   content size 0x02
    //   offset 16:   count = 0x02
    //   offset 17:   typeidx 0x00 (first entry, valid)
    //   (EOF -- second typeidx missing)
    //
    // Pre-fix: parseFunctions() throws on buffer underflow for the second entry;
    //   no SECTION_ENTRY_CORRUPT problem is recorded.
    // Post-fix: parseFunctionsPartial() catches the underflow; one
    //   SECTION_ENTRY_CORRUPT reported with byteOffset == 18 (second entry start);
    //   provider.functions reports the one decoded type.
    // -------------------------------------------------------------------
    private val functionSectionTruncatedMidVector: ByteArray = byteArrayOf(
        // magic + version
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        // type section: type[0] = () -> ()
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        // function section: count=2, only typeidx 0 present
        0x03, 0x02,         // section id + content size 2
        0x02,               // count = 2
        0x00,               // typeidx 0 (valid)
        // EOF -- second typeidx missing
    )

    @Test
    fun function_section_truncated_mid_vector_reports_section_entry_corrupt() {
        val provider = WasmBinaryProvider(functionSectionTruncatedMidVector.toCommonByteBuffer())
        // Pre-fix: parseProblems is empty (parseFunctions throws without a guard).
        val problem = provider.parseProblems.singleOrNull { it.kind == ParseProblemKind.SECTION_ENTRY_CORRUPT }
        assertNotNull(problem, "expected one SECTION_ENTRY_CORRUPT, got ${provider.parseProblems}")
        // Second entry starts at offset 18 (8 header + 6 type section + 1 id + 1 size_leb + 1 count + 1 first entry).
        assertEquals(18, problem.byteOffset, "problem must point at the start of the missing second entry")
    }

    @Test
    fun function_section_prefix_preserved_after_truncation() {
        val provider = WasmBinaryProvider(functionSectionTruncatedMidVector.toCommonByteBuffer())
        // Pre-fix: provider.functions throws or is empty due to the full-parse failure.
        // Post-fix: the first typeidx (0) decoded successfully; type[0] = () -> () maps to one type record.
        assertEquals(1, provider.functions.size,
            "the one decoded function entry must survive as a type record after truncation")
    }

    // -------------------------------------------------------------------
    // Test 11: tag (exception) section with truncated entry
    //
    // Binary: header + tag section id=0x0D, size=0x01, count=1, then EOF.
    // The attribute byte read fails immediately.
    //
    // Layout:
    //   offset 0-7:  magic + version
    //   offset 8:    section id 0x0D (tag/exception)
    //   offset 9:    content size 0x01
    //   offset 10:   count = 0x01
    //   (EOF -- no attribute byte for the tag entry)
    //
    // Pre-fix: scanTags() throws on buffer underflow; no problem is recorded.
    // Post-fix: scanTagsPartial() catches the failure; one SECTION_ENTRY_CORRUPT
    //   reported with byteOffset == 11 (the entry start).
    // -------------------------------------------------------------------
    private val tagSectionTruncatedEntry: ByteArray =
        HEADER + byteArrayOf(
            0x0D, 0x01,     // tag section, content size 1
            0x01,           // count = 1 (no entry bytes follow)
        )

    @Test
    fun tag_section_truncated_entry_reports_section_entry_corrupt() {
        val provider = WasmBinaryProvider(tagSectionTruncatedEntry.toCommonByteBuffer())
        // Pre-fix: parseProblems is empty (scanTags throws without a guard).
        val problem = provider.parseProblems.singleOrNull { it.kind == ParseProblemKind.SECTION_ENTRY_CORRUPT }
        assertNotNull(problem, "expected one SECTION_ENTRY_CORRUPT, got ${provider.parseProblems}")
        // Entry 0 starts at offset 11 (8 header + 1 id + 1 size_leb + 1 count).
        assertEquals(11, problem.byteOffset, "problem must point at the corrupt tag entry start")
    }

    // -------------------------------------------------------------------
    // Test 12: element section with a valid first entry and a corrupt second entry
    //
    // Entry 0: flags=0 (active default), offset expr = i32.const 0 end, 1 funcidx (0).
    // Entry 1: flags=0 (active default), then EOF where the offset expr opcode should be.
    //
    // Layout:
    //   offset 0-7:  magic + version
    //   offset 8-13: type section (1 func type () -> ())
    //   offset 14-17: function section (1 function, type index 0)
    //   offset 18:   section id 0x09 (element)
    //   offset 19:   content size 0x08
    //   offset 20:   count = 0x02
    //   offset 21:   entry 0 flags = 0x00
    //   offset 22-24: i32.const 0 end (0x41 0x00 0x0B)
    //   offset 25:   funcidxs count = 0x01
    //   offset 26:   funcidx 0x00
    //   offset 27:   entry 1 flags = 0x00
    //   (EOF -- no offset expr bytes; parseOpCodes at limit=28 returns empty,
    //    then consumeFuncidxs() calls consumeArray which reads getULEB128() and
    //    hits underflow)
    //
    // Pre-fix: scanElements() reports the failure at elementSection.contentOffset (20),
    //   not at the entry 1 start (27).
    // Post-fix: scanElementsPartial() reports byteOffset == 27 (entry 1 start);
    //   entry 0 is preserved via scanElements().
    // -------------------------------------------------------------------
    private val elementSectionSecondEntryCorrupt: ByteArray = byteArrayOf(
        // magic + version
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        // type section: type[0] = () -> ()
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        // function section: function[0] uses type 0
        0x03, 0x02, 0x01, 0x00,
        // element section: count=2, entry 0 valid, entry 1 corrupt
        0x09, 0x08,                 // section id + content size 8
        0x02,                       // count = 2
        0x00,                       // entry 0: flags = 0 (active default)
        0x41, 0x00, 0x0B,           // offset expr: i32.const 0, end
        0x01, 0x00,                 // funcidxs: count=1, funcidx=0
        0x00,                       // entry 1: flags = 0 (active default) -- then EOF
    )

    @Test
    fun element_section_second_entry_corrupt_reports_init_expr_failure_at_entry_start() {
        val provider = WasmBinaryProvider(elementSectionSecondEntryCorrupt.toCommonByteBuffer())
        val problem = provider.parseProblems.singleOrNull { it.kind == ParseProblemKind.INIT_EXPR_PARSE_FAILURE }
        assertNotNull(problem, "expected one INIT_EXPR_PARSE_FAILURE, got ${provider.parseProblems}")
        // Pre-fix: byteOffset == 20 (contentOffset). Post-fix: byteOffset == 27 (entry 1 start).
        assertEquals(27, problem.byteOffset,
            "problem must point at the corrupt entry start (offset 27), not the section content start")
    }

    @Test
    fun element_section_valid_first_entry_is_preserved() {
        val provider = WasmBinaryProvider(elementSectionSecondEntryCorrupt.toCommonByteBuffer())
        // Pre-fix: scanElements() throws; all entries are lost. Post-fix: entry 0 survives.
        val module = provider.wasmModule
        assertNotNull(module, "module must parse despite the corrupt element entry")
        val elements = with(module.elementSection!!) { provider.bytes.scanElements() }
        assertEquals(1, elements.size, "the valid first element entry must be preserved")
    }

    @Test
    fun component_parse_problems_include_every_malformed_nested_component_in_byte_order() {
        val componentHeader = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)
        val malformedNestedComponent = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val nestedSection = byteArrayOf(0x04, malformedNestedComponent.size.toByte()) + malformedNestedComponent
        val provider = WasmComponentBinaryProvider((componentHeader + nestedSection + nestedSection).toCommonByteBuffer())

        assertEquals(listOf(10, 16), provider.parseProblems.map { it.byteOffset })
    }
}
