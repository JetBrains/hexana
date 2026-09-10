package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dwarf.DebugInformationEntry
import org.jetbrains.hexana.dwarf.DieField
import org.jetbrains.hexana.dwarf.DwarfAbbrevSection
import org.jetbrains.hexana.dwarf.DwarfAccessor
import org.jetbrains.hexana.dwarf.DwarfInfoSection
import org.jetbrains.hexana.dwarf.DwarfLineSection
import org.jetbrains.hexana.dwarf.DwarfLineUnitHeader
import org.jetbrains.hexana.dwarf.Form
import org.jetbrains.hexana.dwarf.LneOpcode
import org.jetbrains.hexana.dwarf.LnsOpcode
import org.jetbrains.hexana.dwarf.LnsSpecialOpcode
import org.jetbrains.hexana.dwarf.StringTableEntry
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * The DWARF section outlines, as rows rather than as a rendered tree.
 *
 * The IntelliJ plugin draws these with Jewel's `LazyTree`, the VS Code webview with a flattened
 * virtualized list. Neither shape is shareable, but the rows are: the label text, the node ids the
 * hosts key expansion and search state on, and the byte range each row navigates to all live here,
 * so the two editors cannot drift apart. Same split as [DexSmaliClass] / `DexSmaliText.kt`.
 */

const val DWARF_GROUP_TAB_NAME: String = "DWARF"
const val DWARF_ABBREV_TAB_NAME: String = ".debug_abbrev"
const val DWARF_INFO_TAB_NAME: String = ".debug_info"
const val DWARF_LINE_TAB_NAME: String = ".debug_line"

/**
 * One row of a DWARF outline.
 *
 * [id] is path-derived and unique across the whole section, not just among siblings — hosts key
 * expansion, selection and search navigation on it, and it stays valid across a reparse.
 *
 * [childrenProvider] is `null` for a leaf and is otherwise **pure and un-memoized**: calling it
 * twice parses twice. Callers cache — Jewel's `Tree.Element.Node` caches `children` itself, and the
 * web side keeps a map — which is also what makes the expensive parts (a `.debug_line` line
 * program) cost nothing until the user expands them.
 */
class DwarfOutlineNode(
    val id: String,
    val label: String,
    /** Absolute file offset to select in the hex view, or `null` for a synthetic row. */
    val offset: Int? = null,
    val size: Int = 0,
    val childrenProvider: (() -> List<DwarfOutlineNode>)? = null,
) {
    val hasChildren: Boolean get() = childrenProvider != null

    fun children(): List<DwarfOutlineNode> = childrenProvider?.invoke() ?: emptyList()
}

/**
 * A DWARF section that has an outline, with [roots] deferred.
 *
 * Deliberately a lambda: hosts enumerate sections just to name tabs (IntelliJ's
 * `discoverDwarfPanelNames` and its UI-test probe both do), and naming a tab must not parse a
 * section.
 */
class DwarfOutlineSection(val name: String, val roots: () -> List<DwarfOutlineNode>)

/** The DWARF sections [accessor] exposes an outline for, in tab order. Reads no section content. */
fun dwarfOutlineSections(accessor: DwarfAccessor, bytes: CommonByteBuffer): List<DwarfOutlineSection> =
    buildList {
        accessor.debugAbbrevSection()?.let { section ->
            add(DwarfOutlineSection(DWARF_ABBREV_TAB_NAME) { dwarfAbbrevOutline(section, bytes) })
        }
        accessor.debugInfoSection()?.let { section ->
            add(DwarfOutlineSection(DWARF_INFO_TAB_NAME) { dwarfInfoOutline(section, bytes) })
        }
        accessor.debugLineSection()?.let { section ->
            add(DwarfOutlineSection(DWARF_LINE_TAB_NAME) { dwarfLineOutline(section, bytes) })
        }
    }

// ---------------------------------------------------------------------------
// .debug_abbrev
// ---------------------------------------------------------------------------

fun dwarfAbbrevOutline(section: DwarfAbbrevSection, bytes: CommonByteBuffer): List<DwarfOutlineNode> {
    val tables = dwarfOutlineCatching(".debug_abbrev") {
        with(section) { bytes.duplicate().scanDwarfAbbrev() }
    } ?: return dwarfOutlineError(".debug_abbrev")

    return tables.map { (tableOffset, entries) ->
        val tableId = "cu@${tableOffset.toString(16)}"
        DwarfOutlineNode(
            id = tableId,
            label = "Compilation Unit @ 0x${tableOffset.toString(16)}",
            childrenProvider = {
                entries.mapIndexed { entryIndex, entry ->
                    val entryId = "$tableId/$entryIndex"
                    val childrenTag = if (entry.hasChildren) "DW_CHILDREN_yes" else "DW_CHILDREN_no"
                    DwarfOutlineNode(
                        id = entryId,
                        label = "[${entry.number}] ${entry.tag.name}  $childrenTag",
                        offset = entry.offset,
                        size = entry.size,
                        childrenProvider = if (entry.attributes.isEmpty()) {
                            null
                        } else {
                            {
                                entry.attributes.mapIndexed { attrIndex, attr ->
                                    val value = attr.value?.let { " = ${it.toHexString()}" } ?: ""
                                    DwarfOutlineNode(
                                        id = "$entryId/$attrIndex",
                                        label = "${attr.name} ${attr.form}$value",
                                        offset = attr.offset,
                                        size = attr.size,
                                    )
                                }
                            }
                        },
                    )
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// .debug_info
// ---------------------------------------------------------------------------

fun dwarfInfoOutline(section: DwarfInfoSection, bytes: CommonByteBuffer): List<DwarfOutlineNode> {
    val headers = dwarfOutlineCatching(".debug_info") {
        with(section) { bytes.duplicate().scanDwarfInfo() }
    } ?: return dwarfOutlineError(".debug_info")

    // Indexed once. The label runs for every visible row and for every row of the search haystack,
    // so a linear scan of the string table per row would be quadratic.
    val stringEntries: Map<UInt, StringTableEntry> = if (section.accessor.debugStrSection() == null) {
        emptyMap()
    } else {
        dwarfOutlineCatching(".debug_str") {
            with(section) { bytes.duplicate().getStringTable() }.associateBy { it.originalOffset }
        } ?: emptyMap()
    }

    return headers.mapIndexedNotNull { headerIndex, header ->
        val root = header.children.firstOrNull() ?: return@mapIndexedNotNull null
        dwarfDieNode(root, "cu/$headerIndex", bytes, stringEntries)
    }
}

private fun dwarfDieNode(
    die: DebugInformationEntry,
    id: String,
    bytes: CommonByteBuffer,
    stringEntries: Map<UInt, StringTableEntry>,
): DwarfOutlineNode = DwarfOutlineNode(
    id = id,
    label = "${die.tag} @ 0x${die.offset.toString(16)}",
    offset = die.offset,
    size = die.size,
    childrenProvider = if (die.attributes.isEmpty() && die.children.isEmpty()) {
        null
    } else {
        {
            buildList {
                die.attributes.forEachIndexed { index, field ->
                    add(
                        DwarfOutlineNode(
                            id = "$id/a$index",
                            label = dwarfDieFieldLabel(field, bytes, stringEntries),
                            // The field's own bytes in `.debug_info`, not `field.attribute`, which
                            // is the shared `.debug_abbrev` declaration and lives in another
                            // section. Forms that occupy no bytes stay non-navigable.
                            offset = field.valueOffset.takeIf { field.valueSize > 0 },
                            size = field.valueSize,
                        )
                    )
                }
                die.children.forEachIndexed { index, child ->
                    add(dwarfDieNode(child, "$id/c$index", bytes, stringEntries))
                }
            }
        }
    },
)

private fun dwarfDieFieldLabel(
    field: DieField,
    bytes: CommonByteBuffer,
    stringEntries: Map<UInt, StringTableEntry>,
): String {
    val attribute = field.attribute
    val base = "${attribute.name} ${attribute.form}"
    if (attribute.form != Form.DW_FORM_strp) return base
    // A DWARF64 unit encodes strp as 8 bytes, which readForm yields as a ULong. `.debug_str`
    // offsets are indexed by UInt, matching StringTableEntry.originalOffset.
    val strpOffset = when (val value = field.value) {
        is UInt -> value
        is ULong -> value.toUInt()
        else -> null
    }
    val resolved = strpOffset
        ?.let { stringEntries[it] }
        ?.let { entry -> dwarfOutlineCatching(".debug_str") { with(entry) { bytes.duplicate().str() } } }
        ?: "Unknown string"
    return "$base ${field.value}: '$resolved'"
}

// ---------------------------------------------------------------------------
// .debug_line
// ---------------------------------------------------------------------------

fun dwarfLineOutline(section: DwarfLineSection, bytes: CommonByteBuffer): List<DwarfOutlineNode> {
    val units = dwarfOutlineCatching(".debug_line") {
        with(section) { bytes.duplicate().scanLineTable() }
    } ?: return dwarfOutlineError(".debug_line")

    return units.mapIndexed { unitIndex, unit ->
        val unitId = "unit/$unitIndex"
        val unitOffset = (unit.contentOffset - section.contentOffset).toHexString()
        DwarfOutlineNode(
            id = unitId,
            label = "debug line [$unitOffset]",
            offset = unit.contentOffset,
            childrenProvider = { dwarfLineUnitRows(section, unit, unitId, bytes) },
        )
    }
}

private fun dwarfLineUnitRows(
    section: DwarfLineSection,
    unit: DwarfLineUnitHeader<*>,
    unitId: String,
    bytes: CommonByteBuffer,
): List<DwarfOutlineNode> = buildList {
    var fieldIndex = 0
    fun field(label: String) = add(DwarfOutlineNode("$unitId/field/${fieldIndex++}", label))

    val (version, addressSize) = when (unit) {
        is DwarfLineUnitHeader.Dwarf32LineUnitHeader.V4 -> 4 to 4
        is DwarfLineUnitHeader.Dwarf64LineUnitHeader.V4 -> 4 to 8
        is DwarfLineUnitHeader.Dwarf32LineUnitHeader.V5 -> 5 to 4
        is DwarfLineUnitHeader.Dwarf64LineUnitHeader.V5 -> 5 to 8
    }
    field("total_length: ${dwarfUnsignedHex(unit.unitSize)}")
    field("format: ${if (addressSize == 8) "DWARF64" else "DWARF32"}")
    field("version: $version")
    field("address size: $addressSize")
    field("prolog length: ${dwarfUnsignedHex(unit.prologLength)}")
    field("maximum_operations_per_instruction: ${unit.maximumOperationsPerInstruction}")
    field("default_is_stmt: ${unit.defaultIsStatement}")
    field("line_base: ${unit.lineBase}")
    field("line_range: ${unit.lineRange}")
    field("program_offset: ${unit.programOffset}")

    add(
        DwarfOutlineNode(
            id = "$unitId/opcode_lengths",
            label = "standard_opcode_lengths",
            childrenProvider = {
                unit.standardOpcodeLengths.mapIndexed { index, value ->
                    DwarfOutlineNode(
                        id = "$unitId/opcode_lengths/$index",
                        label = "${dwarfStandardOpcodeName(index)}: $value",
                    )
                }
            },
        )
    )

    // The expensive one, and the reason childrenProvider exists: a line program is a very wide
    // level that is only parsed if someone opens it.
    add(
        DwarfOutlineNode(
            id = "$unitId/line_program",
            label = "line_program",
            childrenProvider = {
                val opcodes = dwarfOutlineCatching(".debug_line") {
                    with(unit) { bytes.duplicate().lineProgram() }
                } ?: return@DwarfOutlineNode dwarfOutlineError(".debug_line")
                opcodes.mapIndexed { index, opcode ->
                    val offset = (opcode.offset - section.offset).toHexString()
                    val label = when (opcode) {
                        is LnsSpecialOpcode -> "$offset: ${opcode.value.toHexString()}"
                        is LnsOpcode -> "$offset: ${opcode::class.simpleName}"
                        is LneOpcode -> "$offset: ${opcode::class.simpleName}"
                    }
                    DwarfOutlineNode(
                        id = "$unitId/line_program/$index",
                        label = label,
                        offset = opcode.offset,
                        size = opcode.size,
                    )
                }
            },
        )
    )
}

private fun dwarfStandardOpcodeName(index: Int): String = when (index) {
    LnsOpcode.DW_LNS_copy.standardOpcodeLengthsEncoding -> "DW_LNS_copy"
    LnsOpcode.DW_LNS_advance_pc.standardOpcodeLengthsEncoding -> "DW_LNS_advance_pc"
    LnsOpcode.DW_LNS_advance_line.standardOpcodeLengthsEncoding -> "DW_LNS_advance_line"
    LnsOpcode.DW_LNS_set_file.standardOpcodeLengthsEncoding -> "DW_LNS_set_file"
    LnsOpcode.DW_LNS_set_column.standardOpcodeLengthsEncoding -> "DW_LNS_set_column"
    LnsOpcode.DW_LNS_negate_stmt.standardOpcodeLengthsEncoding -> "DW_LNS_negate_stmt"
    LnsOpcode.DW_LNS_set_basic_block.standardOpcodeLengthsEncoding -> "DW_LNS_set_basic_block"
    LnsOpcode.DW_LNS_const_add_pc.standardOpcodeLengthsEncoding -> "DW_LNS_const_add_pc"
    LnsOpcode.DW_LNS_fixed_advance_pc.standardOpcodeLengthsEncoding -> "DW_LNS_fixed_advance_pc"
    LnsOpcode.DW_LNS_set_prologue_end.standardOpcodeLengthsEncoding -> "DW_LNS_set_prologue_end"
    LnsOpcode.DW_LNS_set_epilogue_begin.standardOpcodeLengthsEncoding -> "DW_LNS_set_epilogue_begin"
    LnsOpcode.DW_LNS_set_isa.standardOpcodeLengthsEncoding -> "DW_LNS_set_isa"
    else -> "opcode $index"
}

private fun dwarfUnsignedHex(value: Any?): String = when (value) {
    is UInt -> value.toHexString()
    is ULong -> value.toHexString()
    else -> value.toString()
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

/**
 * Every row of an outline, collapsed subtrees included, with the parent links needed to reveal a
 * hit. Built by [dwarfSearchIndex].
 */
class DwarfSearchIndex(
    private val ids: List<String>,
    private val labels: List<String>,
    private val parents: Map<String, String>,
    /** True when [limit] was hit and the tail of the tree is not searchable. */
    val truncated: Boolean,
) {
    val size: Int get() = ids.size

    /** Ids of the rows whose label contains [needle], in tree order. */
    fun idsMatching(needle: String): List<String> = buildList {
        for (index in ids.indices) {
            if (labels[index].contains(needle, ignoreCase = true)) add(ids[index])
        }
    }

    /** [id]'s ancestors, nearest first — the nodes a host must open to reveal it. */
    fun ancestorsOf(id: String): List<String> =
        generateSequence(parents[id]) { parents[it] }.toList()
}

/**
 * Walks the whole outline, forcing every [DwarfOutlineNode.childrenProvider], and records one entry
 * per row.
 *
 * This is the expensive call in the feature: it is what makes a hit inside a collapsed subtree
 * findable, and there is no way to search text that has not been generated. Hosts defer it until
 * the user actually types. [limit] bounds a pathological binary rather than letting the walk eat
 * the heap; past it the index reports [DwarfSearchIndex.truncated].
 */
fun dwarfSearchIndex(roots: List<DwarfOutlineNode>, limit: Int = 200_000): DwarfSearchIndex {
    val ids = ArrayList<String>()
    val labels = ArrayList<String>()
    val parents = HashMap<String, String>()
    var truncated = false

    fun visit(node: DwarfOutlineNode, parentId: String?) {
        if (ids.size >= limit) {
            truncated = true
            return
        }
        ids.add(node.id)
        labels.add(node.label)
        if (parentId != null) parents[node.id] = parentId
        for (child in node.children()) {
            if (truncated) return
            visit(child, node.id)
        }
    }
    for (root in roots) {
        if (truncated) break
        visit(root, null)
    }

    return DwarfSearchIndex(ids, labels, parents, truncated)
}

// ---------------------------------------------------------------------------
// Failure handling
// ---------------------------------------------------------------------------

/**
 * A malformed section must not take the editor down with it. The DWARF parsers throw on an
 * unsupported version or a missing abbrev, which on the JVM is an IDE error but in a webview is an
 * uncaught throw out of composition.
 */
private inline fun <T> dwarfOutlineCatching(section: String, body: () -> T): T? =
    try {
        body()
    } catch (_: Throwable) {
        null
    }

private fun dwarfOutlineError(section: String): List<DwarfOutlineNode> =
    listOf(DwarfOutlineNode(id = "error", label = "$section could not be decoded"))
