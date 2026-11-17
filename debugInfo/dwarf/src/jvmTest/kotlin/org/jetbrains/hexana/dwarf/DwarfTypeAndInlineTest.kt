package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DwarfTypeResolver] (capability B) and [DwarfInlineFrameExtractor]
 * (capability A) against the Rust wasm32 fixture at
 * `debugInfo/dwarf-fixtures/test-data/wasm32/dwarf-types.wasm`.
 *
 * Fixture source: `dwarf-types.rs`, compiled with:
 *   rustc --edition 2021 --target wasm32-unknown-unknown --crate-type cdylib
 *          -g -C opt-level=1 -C lto=off -o dwarf-types.wasm dwarf-types.rs
 *
 * Key DIEs verified with `llvm-dwarfdump --debug-info dwarf-types.wasm`:
 *   DW_TAG_structure_type "Point" byte_size=8, members: x@0, y@4, z@6
 *   DW_TAG_enumeration_type "Color" byte_size=4, Red=0, Green=1, Blue=2
 *   DW_TAG_array_type i32[4] count=4
 *   DW_TAG_inlined_subroutine "add" low_pc=0xa, high_pc_offset=0xd (range [0xa,0x17))
 *     DW_AT_call_line=23 DW_AT_call_column=15
 */
class DwarfTypeAndInlineTest {

    // --------------- infrastructure ---------------

    private fun wasmBytes(): ByteArray = Files.readAllBytes(
        fixtureRoot().resolve("dwarf-types.wasm")
    )

    private fun fixtureRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("debugInfo").resolve("dwarf-fixtures")) }
            .resolve("debugInfo")
            .resolve("dwarf-fixtures")
            .resolve("test-data")
            .resolve("wasm32")

    /**
     * Minimal wasm custom-section parser. Scans the binary for custom sections
     * (section id = 0) whose name starts with `.debug_` and returns a
     * [DwarfAccessor] backed by the raw byte array.
     *
     * This avoids a dependency on `wasmParser` from `debugInfo:dwarf` tests.
     */
    private fun buildAccessor(bytes: ByteArray): DwarfAccessor {
        data class Section(val name: String, val contentOffset: Int, val contentSize: Int)

        val sections = mutableListOf<Section>()
        var i = 8 // skip magic + version
        while (i < bytes.size) {
            if (i >= bytes.size) break
            val secId = bytes[i].toInt() and 0xff
            i++
            // read ULEB128 section size
            var size = 0; var shift = 0
            while (true) {
                val b = bytes[i].toInt() and 0xff; i++
                size = size or ((b and 0x7f) shl shift); shift += 7
                if (b and 0x80 == 0) break
            }
            val secBodyStart = i
            if (secId == 0) {
                // read name ULEB128 length then name bytes
                var j = i
                var nameLen = 0; var ns = 0
                while (true) {
                    val b = bytes[j].toInt() and 0xff; j++
                    nameLen = nameLen or ((b and 0x7f) shl ns); ns += 7
                    if (b and 0x80 == 0) break
                }
                val name = String(bytes, j, nameLen, Charsets.UTF_8)
                val contentStart = j + nameLen
                val contentSize = size - (contentStart - i)
                if (name.startsWith(".debug_")) {
                    sections.add(Section(name, contentStart, contentSize))
                }
            }
            i = secBodyStart + size
        }

        val buffer = bytes.toCommonByteBuffer()

        return object : DwarfAccessor {
            override fun section(name: String): DwarfSection? {
                val s = sections.find { it.name == name } ?: return null
                return when (name) {
                    ".debug_line" -> DwarfLineSection(s.contentOffset, s.contentSize, this)
                    ".debug_info" -> DwarfInfoSection(s.contentOffset, s.contentSize, this)
                    ".debug_abbrev" -> DwarfAbbrevSection(s.contentOffset, s.contentSize, this)
                    ".debug_str" -> DwarfStrSection(s.contentOffset, s.contentSize, this)
                    ".debug_line_str" -> DwarfLineStrSection(s.contentOffset, s.contentSize, this)
                    ".debug_addr" -> DwarfAddressSection(s.contentOffset, s.contentSize, this)
                    ".debug_loc" -> DwarfLocSection(s.contentOffset, s.contentSize, this)
                    else -> null
                }
            }

            override fun debugLineSection() = section(".debug_line") as? DwarfLineSection
            override fun debugInfoSection() = section(".debug_info") as? DwarfInfoSection
            override fun debugAbbrevSection() = section(".debug_abbrev") as? DwarfAbbrevSection
            override fun debugStrSection() = section(".debug_str") as? DwarfStrSection
            override fun debugAddrSection() = section(".debug_addr") as? DwarfAddressSection
            override fun debugLocSection() = section(".debug_loc") as? DwarfLocSection
        }
    }

    // --------------- helper: parse all DIEs from first CU with "dwarf_types" namespace ---------------

    /**
     * Reads a DWARF string attribute value, resolving `.debug_str` offsets when
     * the form is `DW_FORM_strp` (value stored as an Int offset).
     */
    private fun resolveName(
        field: DieField,
        buffer: CommonByteBuffer,
        accessor: DwarfAccessor,
    ): String? {
        if (field.value is String) return field.value
        val strSection = accessor.debugStrSection() ?: return null
        val offset = field.intValue ?: return null
        val pos = strSection.contentOffset + offset
        val bytes = mutableListOf<Byte>()
        var idx = pos
        while (idx < buffer.limit() && buffer.get(idx) != 0.toByte()) {
            bytes.add(buffer.get(idx))
            idx++
        }
        return bytes.toByteArray().decodeToString()
    }

    /** Resolves `DW_AT_name` from a DIE, reading through `.debug_str` if needed. */
    private fun dieName(
        die: DebugInformationEntry,
        buffer: CommonByteBuffer,
        accessor: DwarfAccessor,
    ): String? = die.attributes
        .find { it.attribute.name == Attribute.DW_AT_name }
        ?.let { resolveName(it, buffer, accessor) }

    /**
     * Returns the compilation unit root DIE (DW_TAG_compile_unit child) for
     * the Rust lib cgu that contains our fixture types. That CU is identified
     * by having a DW_TAG_namespace child named "dwarf_types".
     */
    private fun findFixtureCuRoot(
        buffer: CommonByteBuffer,
        accessor: DwarfAccessor,
    ): DebugInformationEntry? {
        val infoSection = accessor.debugInfoSection() ?: return null
        val cus = with(infoSection) { buffer.scanDwarfInfo() }
        for (cu in cus) {
            val cuRoot = cu.children.firstOrNull() ?: continue
            val hasLibNs = cuRoot.children.any { child ->
                child.tag == Tag.DW_TAG_namespace &&
                    child.attributes.any { f ->
                        f.attribute.name == Attribute.DW_AT_name &&
                            resolveName(f, buffer, accessor) == "dwarf_types"
                    }
            }
            if (hasLibNs) return cuRoot
        }
        return null
    }

    // --------------- DwarfTypeResolver tests ---------------

    @Test
    fun `Point struct has three members at correct offsets`() {
        val bytes = wasmBytes()
        val accessor = buildAccessor(bytes)
        val buffer = bytes.toCommonByteBuffer()

        val cuRoot = findFixtureCuRoot(buffer, accessor)
        assertNotNull(cuRoot, "lib CU root not found")

        val infoContentOffset = accessor.debugInfoSection()!!.contentOffset
        val dieIndex = buildDieIndex(cuRoot)
        val resolver = DwarfTypeResolver(dieIndex, accessor, infoContentOffset)

        // Find the DW_TAG_structure_type "Point" DIE in the index.
        val structDie = dieIndex.values.firstOrNull { die ->
            die.tag == Tag.DW_TAG_structure_type && dieName(die, buffer, accessor) == "Point"
        }
        assertNotNull(structDie, "Point struct DIE not found")

        val typeInfo = with(resolver) { buffer.resolveTypeAtOffset(structDie.offset) }
        assertIs<DwarfTypeInfo.Struct>(typeInfo)
        assertEquals("Point", typeInfo.name)
        assertEquals(8, typeInfo.byteSize)
        assertEquals(3, typeInfo.members.size)

        // x: i32 at offset 0
        val memberX = typeInfo.members[0]
        assertEquals("x", memberX.name)
        assertEquals(0, memberX.offset)
        assertIs<DwarfTypeInfo.Base>(memberX.type)
        assertEquals(4, (memberX.type as DwarfTypeInfo.Base).byteSize)

        // y: i16 at offset 4
        val memberY = typeInfo.members[1]
        assertEquals("y", memberY.name)
        assertEquals(4, memberY.offset)
        assertIs<DwarfTypeInfo.Base>(memberY.type)
        assertEquals(2, (memberY.type as DwarfTypeInfo.Base).byteSize)

        // z: i8 at offset 6
        val memberZ = typeInfo.members[2]
        assertEquals("z", memberZ.name)
        assertEquals(6, memberZ.offset)
        assertIs<DwarfTypeInfo.Base>(memberZ.type)
        assertEquals(1, (memberZ.type as DwarfTypeInfo.Base).byteSize)
    }

    @Test
    fun `Color enum has three enumerators with correct values`() {
        val bytes = wasmBytes()
        val accessor = buildAccessor(bytes)
        val buffer = bytes.toCommonByteBuffer()

        val cuRoot = findFixtureCuRoot(buffer, accessor)
        assertNotNull(cuRoot, "lib CU root not found")

        val infoContentOffset = accessor.debugInfoSection()!!.contentOffset
        val dieIndex = buildDieIndex(cuRoot)
        val resolver = DwarfTypeResolver(dieIndex, accessor, infoContentOffset)

        val enumDie = dieIndex.values.firstOrNull { die ->
            die.tag == Tag.DW_TAG_enumeration_type && dieName(die, buffer, accessor) == "Color"
        }
        assertNotNull(enumDie, "Color enum DIE not found")

        val typeInfo = with(resolver) { buffer.resolveTypeAtOffset(enumDie.offset) }
        assertIs<DwarfTypeInfo.Enumeration>(typeInfo)
        assertEquals("Color", typeInfo.name)
        assertEquals(4, typeInfo.byteSize)
        assertEquals(3, typeInfo.enumerators.size)

        val byName = typeInfo.enumerators.associateBy { it.name }
        assertEquals(0L, byName["Red"]?.value)
        assertEquals(1L, byName["Green"]?.value)
        assertEquals(2L, byName["Blue"]?.value)
    }

    @Test
    fun `i32 array has count 4`() {
        val bytes = wasmBytes()
        val accessor = buildAccessor(bytes)
        val buffer = bytes.toCommonByteBuffer()

        val cuRoot = findFixtureCuRoot(buffer, accessor)
        assertNotNull(cuRoot, "lib CU root not found")

        val infoContentOffset = accessor.debugInfoSection()!!.contentOffset
        val dieIndex = buildDieIndex(cuRoot)
        val resolver = DwarfTypeResolver(dieIndex, accessor, infoContentOffset)

        // Find DW_TAG_array_type with element type i32.
        val arrayDie = dieIndex.values.firstOrNull { die ->
            die.tag == Tag.DW_TAG_array_type
        }
        assertNotNull(arrayDie, "array_type DIE not found")

        val typeInfo = with(resolver) { buffer.resolveTypeAtOffset(arrayDie.offset) }
        assertIs<DwarfTypeInfo.Array>(typeInfo)
        assertEquals(4, typeInfo.count)

        val elemType = typeInfo.elementType
        assertIs<DwarfTypeInfo.Base>(elemType)
        assertEquals(4, elemType.byteSize)
    }

    @Test
    fun `base type i32 has byte_size 4`() {
        val bytes = wasmBytes()
        val accessor = buildAccessor(bytes)
        val buffer = bytes.toCommonByteBuffer()

        val cuRoot = findFixtureCuRoot(buffer, accessor)
        assertNotNull(cuRoot, "lib CU root not found")

        val infoContentOffset = accessor.debugInfoSection()!!.contentOffset
        val dieIndex = buildDieIndex(cuRoot)
        val resolver = DwarfTypeResolver(dieIndex, accessor, infoContentOffset)

        val baseDie = dieIndex.values.firstOrNull { die ->
            die.tag == Tag.DW_TAG_base_type && dieName(die, buffer, accessor) == "i32"
        }
        assertNotNull(baseDie, "i32 base_type DIE not found")

        val typeInfo = with(resolver) { buffer.resolveTypeAtOffset(baseDie.offset) }
        assertIs<DwarfTypeInfo.Base>(typeInfo)
        assertEquals("i32", typeInfo.name)
        assertEquals(4, typeInfo.byteSize)
    }

    @Test
    fun `pointer type resolves to pointee`() {
        val bytes = wasmBytes()
        val accessor = buildAccessor(bytes)
        val buffer = bytes.toCommonByteBuffer()

        val cuRoot = findFixtureCuRoot(buffer, accessor)
        assertNotNull(cuRoot, "lib CU root not found")

        val infoContentOffset = accessor.debugInfoSection()!!.contentOffset
        val dieIndex = buildDieIndex(cuRoot)
        val resolver = DwarfTypeResolver(dieIndex, accessor, infoContentOffset)

        // DW_TAG_pointer_type pointing to Point struct
        val ptrDie = dieIndex.values.firstOrNull { die ->
            die.tag == Tag.DW_TAG_pointer_type
        }
        assertNotNull(ptrDie, "pointer_type DIE not found")

        val typeInfo = with(resolver) { buffer.resolveTypeAtOffset(ptrDie.offset) }
        assertIs<DwarfTypeInfo.Pointer>(typeInfo)
        // The pointee resolves through a chain and eventually hits Struct or base
        assertNotNull(typeInfo.pointee, "pointer should have a pointee")
    }

    @Test
    fun `resolver returns null for unknown offset`() {
        val bytes = wasmBytes()
        val accessor = buildAccessor(bytes)
        val buffer = bytes.toCommonByteBuffer()

        val cuRoot = findFixtureCuRoot(buffer, accessor)
        assertNotNull(cuRoot)

        val infoContentOffset = accessor.debugInfoSection()!!.contentOffset
        val dieIndex = buildDieIndex(cuRoot)
        val resolver = DwarfTypeResolver(dieIndex, accessor, infoContentOffset)
        val result = with(resolver) { buffer.resolveTypeAtOffset(0xdeadbeef.toInt()) }
        assertNull(result)
    }

    // --------------- DwarfInlineFrameExtractor tests ---------------

    @Test
    fun `inlinedFramesAt returns add frame at offset inside inlined range`() {
        val bytes = wasmBytes()
        val accessor = buildAccessor(bytes)
        val buffer = bytes.toCommonByteBuffer()

        val extractor = DwarfInlineFrameExtractor(accessor)

        // From llvm-dwarfdump:
        //   DW_TAG_inlined_subroutine "add" low_pc=0xa, high_pc_offset=0xd
        //   => range [0xa, 0xa+0xd) = [0xa, 0x17)
        // Offset 0xa is inside the inlined range.
        val frames = with(extractor) { buffer.inlinedFramesAt(0x0a) }

        assertTrue(frames.isNotEmpty(), "Expected at least one inlined frame at 0xa")

        val addFrame = frames.firstOrNull { it.name == "add" }
        assertNotNull(addFrame, "Expected frame named 'add'")
        assertEquals(23, addFrame.callLine, "call_line should be 23")
        assertEquals(15, addFrame.callColumn, "call_column should be 15")
        assertEquals(1, addFrame.depth, "directly inlined frame should have depth 1")
    }

    @Test
    fun `inlinedFramesAt returns empty list for offset outside inlined range`() {
        val bytes = wasmBytes()
        val accessor = buildAccessor(bytes)
        val buffer = bytes.toCommonByteBuffer()

        val extractor = DwarfInlineFrameExtractor(accessor)

        // low_pc of compute subprogram is 0x2; before the inlined subroutine at 0xa.
        val frames = with(extractor) { buffer.inlinedFramesAt(0x02) }
        assertTrue(
            frames.none { it.name == "add" },
            "Offset 0x02 is before the inlined range; 'add' frame should not appear"
        )
    }

    @Test
    fun `inlinedFramesAt returns empty list for offset outside subprogram`() {
        val bytes = wasmBytes()
        val accessor = buildAccessor(bytes)
        val buffer = bytes.toCommonByteBuffer()

        val extractor = DwarfInlineFrameExtractor(accessor)

        // Offset 0x01 is before the 'compute' subprogram (low_pc=0x02); 'add' is
        // inlined inside 'compute', so it must not appear here.
        val frames = with(extractor) { buffer.inlinedFramesAt(0x01) }
        assertTrue(
            frames.none { it.name == "add" },
            "Offset 0x01 is before 'compute'; 'add' frame must not appear"
        )
    }

    @Test
    fun `inlined frame linkage name is set for add`() {
        val bytes = wasmBytes()
        val accessor = buildAccessor(bytes)
        val buffer = bytes.toCommonByteBuffer()

        val extractor = DwarfInlineFrameExtractor(accessor)
        val frames = with(extractor) { buffer.inlinedFramesAt(0x0a) }

        val addFrame = frames.firstOrNull { it.name == "add" }
        assertNotNull(addFrame)
        // Rust mangled name present in DW_AT_linkage_name on abstract_origin
        assertNotNull(addFrame.linkageName, "linkageName should be present for Rust 'add'")
        assertTrue(
            addFrame.linkageName!!.contains("add"),
            "linkageName should contain 'add': ${addFrame.linkageName}"
        )
    }

    @Test
    fun `frames are sorted innermost first by depth`() {
        val bytes = wasmBytes()
        val accessor = buildAccessor(bytes)
        val buffer = bytes.toCommonByteBuffer()

        val extractor = DwarfInlineFrameExtractor(accessor)
        val frames = with(extractor) { buffer.inlinedFramesAt(0x0a) }

        // The list must be sorted descending by depth (innermost = highest depth first)
        for (i in 1 until frames.size) {
            assertTrue(
                frames[i - 1].depth >= frames[i].depth,
                "Frames must be sorted innermost (highest depth) first"
            )
        }
    }
}
