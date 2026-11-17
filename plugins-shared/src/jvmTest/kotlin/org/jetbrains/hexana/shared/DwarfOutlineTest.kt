package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the DWARF outline both editors render from.
 *
 * The label strings are asserted rather than merely smoke-tested because they are the whole point
 * of the shared model: the IntelliJ tree and the VS Code tree must show the same text, and search
 * matches against exactly these strings.
 */
class DwarfOutlineTest {

    /** A fixture from another module's test data; the test runs with the module dir as cwd. */
    private fun fixture(relativePath: String): File =
        File(relativePath).also { assertTrue(it.exists(), "test fixture must exist: ${it.absolutePath}") }

    private fun sections(): Pair<List<DwarfOutlineSection>, org.jetbrains.hexana.io.CommonByteBuffer> {
        val bytes = fixture("../ui-driver-tests/testData/projects/hexana_wasm_project/debug-add.wasm").readBytes()
        val buffer = bytes.toCommonByteBuffer()
        val parsed = WasmParser().parse(buffer.duplicate().position(0))
        val module = assertNotNull(parsed as? WasmParseSuccess, "fixture must parse").wasmModule
        val accessor = WasmDwarfAccessor(module as BaseModule)
        return dwarfOutlineSections(accessor, buffer) to buffer
    }

    private fun section(name: String): List<DwarfOutlineNode> {
        val (sections, _) = sections()
        return assertNotNull(sections.find { it.name == name }, "$name must have an outline").roots()
    }

    private fun walk(roots: List<DwarfOutlineNode>): List<DwarfOutlineNode> = buildList {
        fun visit(node: DwarfOutlineNode) {
            add(node)
            node.children().forEach(::visit)
        }
        roots.forEach(::visit)
    }

    @Test
    fun `all three dwarf sections are outlined in tab order`() {
        val (sections, _) = sections()
        assertEquals(
            listOf(DWARF_ABBREV_TAB_NAME, DWARF_INFO_TAB_NAME, DWARF_LINE_TAB_NAME),
            sections.map { it.name },
        )
    }

    @Test
    fun `abbrev rows carry the entry number, tag and children flag`() {
        val roots = section(DWARF_ABBREV_TAB_NAME)
        assertTrue(roots.isNotEmpty(), "expected at least one abbrev table")
        assertTrue(
            roots.first().label.startsWith("Compilation Unit @ 0x"),
            "unexpected abbrev root label: ${roots.first().label}",
        )

        val entry = assertNotNull(roots.first().children().firstOrNull(), "expected an abbrev entry")
        assertTrue(
            Regex("""^\[\d+] DW_TAG_\w+ {2}DW_CHILDREN_(yes|no)$""").matches(entry.label),
            "unexpected abbrev entry label: ${entry.label}",
        )
        assertNotNull(entry.offset, "abbrev entries navigate to their bytes")
    }

    /** Guards `Form.toString()`: without it these rows read `Form$DW_FORM_strp@1b6d`. */
    @Test
    fun `abbrev attribute rows name the attribute and its form`() {
        val attributes = walk(section(DWARF_ABBREV_TAB_NAME)).filter { it.label.contains("DW_FORM_") }
        assertTrue(attributes.isNotEmpty(), "expected abbrev attribute rows")
        // `DW_AT_user_defined(2136)` is Attribute.toString()'s rendering of an unknown attribute.
        val shape = Regex("""^(DW_AT_\w+|DW_AT_user_defined\([0-9a-f]+\)) DW_FORM_\w+( = [0-9a-f]+)?$""")
        val malformed = attributes.filterNot { shape.matches(it.label) }
        assertTrue(malformed.isEmpty(), "unexpected attribute labels: ${malformed.map { it.label }}")
    }

    @Test
    fun `info rows name the tag and resolve strp strings`() {
        val roots = section(DWARF_INFO_TAB_NAME)
        assertTrue(roots.isNotEmpty(), "expected at least one compilation unit")
        assertTrue(
            Regex("""^DW_TAG_\w+ @ 0x[0-9a-f]+$""").matches(roots.first().label),
            "unexpected DIE label: ${roots.first().label}",
        )

        val resolved = walk(roots).filter { it.label.contains("DW_FORM_strp") }
        assertTrue(resolved.isNotEmpty(), "fixture is expected to use DW_FORM_strp")
        val unresolved = resolved.filter { it.label.endsWith("'Unknown string'") }
        assertTrue(unresolved.isEmpty(), "every strp offset should resolve: ${unresolved.map { it.label }}")
    }

    /**
     * Regression: these rows used to carry `DieField.attribute.offset`, which is the shared
     * `.debug_abbrev` declaration - so clicking an attribute jumped into the wrong section, and
     * every DIE sharing an abbrev code jumped to the same bytes.
     */
    @Test
    fun `info attribute rows address their own bytes inside debug_info`() {
        val bytes = fixture("../ui-driver-tests/testData/projects/hexana_wasm_project/debug-add.wasm").readBytes()
        val buffer = bytes.toCommonByteBuffer()
        val parsed = WasmParser().parse(buffer.duplicate().position(0))
        val module = assertNotNull(parsed as? WasmParseSuccess, "fixture must parse").wasmModule
        val accessor = WasmDwarfAccessor(module as BaseModule)
        val info = assertNotNull(accessor.debugInfoSection(), "fixture must have .debug_info")
        val abbrev = assertNotNull(accessor.debugAbbrevSection(), "fixture must have .debug_abbrev")

        val infoRange = info.contentOffset until (info.contentOffset + info.contentSize)
        val abbrevRange = abbrev.contentOffset until (abbrev.contentOffset + abbrev.contentSize)

        val roots = dwarfOutlineSections(accessor, buffer)
            .first { it.name == DWARF_INFO_TAB_NAME }
            .roots()
        val addressable = walk(roots).filter { it.label.startsWith("DW_AT_") && it.offset != null }
        assertTrue(addressable.isNotEmpty(), "expected navigable attribute rows")

        val outside = addressable.filterNot { it.offset!! in infoRange }
        assertTrue(outside.isEmpty(), "attribute rows must point inside .debug_info: ${outside.map { it.label to it.offset }}")
        assertTrue(
            addressable.none { it.offset!! in abbrevRange },
            "attribute rows must not point into .debug_abbrev",
        )
    }

    /** Distinct DIEs must address distinct bytes; the shared abbrev declaration collapsed them. */
    @Test
    fun `attribute rows of different DIEs address different bytes`() {
        val offsets = walk(section(DWARF_INFO_TAB_NAME))
            .filter { it.label.startsWith("DW_AT_") }
            .mapNotNull { it.offset }
        assertTrue(offsets.size > 1, "need several attribute rows to compare")
        assertEquals(offsets.size, offsets.toSet().size, "each attribute row must have its own offset")
    }

    @Test
    fun `line units expose their header fields and a line program`() {
        val roots = section(DWARF_LINE_TAB_NAME)
        assertTrue(roots.isNotEmpty(), "expected at least one line unit")
        assertTrue(roots.first().label.startsWith("debug line ["), "unexpected unit label: ${roots.first().label}")

        val rows = roots.first().children().map { it.label }
        assertTrue(rows.any { it.startsWith("version: ") }, "expected a version row, got $rows")
        assertTrue(rows.contains("standard_opcode_lengths"), "expected the opcode-lengths node, got $rows")
        assertTrue(rows.contains("line_program"), "expected the line-program node, got $rows")
    }

    /**
     * `.debug_line` rows were unreachable before the shared model: the IntelliJ click handler
     * matched on DIE types that tree never contained, so no row navigated anywhere.
     */
    @Test
    fun `line program opcodes navigate to their bytes`() {
        val program = assertNotNull(
            walk(section(DWARF_LINE_TAB_NAME)).find { it.label == "line_program" },
            "expected a line_program node",
        )
        val opcodes = program.children()
        assertTrue(opcodes.isNotEmpty(), "expected decoded opcodes")
        opcodes.forEach {
            assertNotNull(it.offset, "opcode row must be addressable: ${it.label}")
            assertTrue(it.size > 0, "opcode row must have a size: ${it.label}")
        }
    }

    @Test
    fun `node ids are unique across the whole tree, not just among siblings`() {
        for (name in listOf(DWARF_ABBREV_TAB_NAME, DWARF_INFO_TAB_NAME, DWARF_LINE_TAB_NAME)) {
            val ids = walk(section(name)).map { it.id }
            assertEquals(ids.size, ids.toSet().size, "$name has duplicate node ids")
        }
    }

    @Test
    fun `search reaches rows inside collapsed subtrees and can reveal them`() {
        val roots = section(DWARF_INFO_TAB_NAME)
        val index = dwarfSearchIndex(roots)
        assertTrue(index.size > roots.size, "the index must cover more than the roots")

        val matches = index.idsMatching("DW_AT_name")
        assertTrue(matches.isNotEmpty(), "expected DW_AT_name rows")

        // A match below the root is only useful if the host can open its way down to it.
        val nested = assertNotNull(
            matches.firstOrNull { index.ancestorsOf(it).isNotEmpty() },
            "expected a match below the root",
        )
        val ancestors = index.ancestorsOf(nested)
        assertTrue(ancestors.contains(roots.first().id), "the ancestor chain must reach a root: $ancestors")
    }

    @Test
    fun `search is case insensitive`() {
        val index = dwarfSearchIndex(section(DWARF_ABBREV_TAB_NAME))
        assertEquals(index.idsMatching("dw_form_strp"), index.idsMatching("DW_FORM_strp"))
    }
}
