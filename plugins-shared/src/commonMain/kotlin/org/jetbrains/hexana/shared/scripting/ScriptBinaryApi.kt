package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryStringRecord
import org.jetbrains.hexana.providers.StructureRange
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsContext
import org.jetbrains.hexana.shared.TableTabsProviderRegistry
import org.jetbrains.hexana.shared.binaryFormatId
import org.jetbrains.hexana.shared.fileTypeDescription
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode

/**
 * Builds the `binary` global handed to a user script.
 *
 * This is the single definition of the scripting surface. Every host binds the
 * returned tree through a thin adapter, so a script sees the same `binary` in the
 * IDE, in VS Code, and in the CLI — which was not true before this existed.
 *
 * The surface is deliberately format-agnostic: `binary.format`,
 * `binary.structure`, `binary.strings`, and `binary.tabs`/`binary.tab(name)`
 * answer the same questions for a Wasm module, an ELF executable, and a ZIP
 * archive. Format-specific data is still reachable two ways:
 *
 * - `binary.raw` — the underlying provider, unfiltered.
 * - any member not listed here falls through to that same provider, so scripts
 *   written against the pre-v1 surface keep working unchanged.
 *
 * Everything is lazy. Listing `binary.tabs` does not compute any table, and
 * resolving one table does not compute the others.
 *
 * @param provider the parsed binary
 * @param fileName display name for `binary.name`, when the host knows it. Only
 *   its last path segment is used, so a host that has nothing but a full path may
 *   pass that.
 * @param filePath absolute path of the file for `binary.path`, when the host has
 *   one. Null is the honest answer for a binary with no file behind it — a ZIP
 *   entry or a nested module opened from a table, or anything in the browser.
 */
fun scriptBinaryApi(
    provider: BinaryProvider,
    fileName: String? = null,
    filePath: String? = null,
): ScriptValue.Obj {
    // A host may know the name, the path, or both; `name` is derived from
    // whichever it gave, so every host reports the same thing for one file.
    // Before this, VS Code passed a full fsPath as the name and reported it
    // as `binary.name` while the IDE and the CLI reported a bare name.
    //
    // Blank is treated as absent, here rather than at each call site: both UI
    // hosts default these to "" before a file is loaded, and a script must not
    // have to tell an empty name from a missing one.
    val path = filePath?.takeIf { it.isNotBlank() }
    val name = (fileName?.takeIf { it.isNotBlank() } ?: path)
        ?.let(::scriptFileBaseName)
        ?.takeIf { it.isNotEmpty() }
    val context = TableTabsContext(fileName = name)
    val bytes = ScriptBytes(provider)

    return ScriptValue.Obj(
        members = linkedMapOf(
            "format" to { ScriptValue.Str(binaryFormatId(provider)) },
            "formatName" to { ScriptValue.Str(fileTypeDescription(provider, name)) },
            "name" to { ScriptValue.of(name) },
            "path" to { ScriptValue.of(path) },
            "dir" to { ScriptValue.of(path?.let(::scriptFileDirectory)) },
            "size" to { ScriptValue.Num(bytes.buffer.size) },
            "bytes" to { bytes.toScriptValue() },
            "structure" to {
                ScriptValue.array(provider.structureRanges, ::structureRangeValue)
            },
            "strings" to {
                ScriptValue.array(provider.strings, ::stringRecordValue)
            },
            "sizeAnalysis" to {
                provider.sizeAnalysis?.let(::sizeNodeValue) ?: ScriptValue.Null
            },
            "tabs" to { ScriptValue.strings(scriptTabNames(provider, context)) },
            "tab" to {
                scriptFunction("binary.tab(name)", minArgs = 1) { args ->
                    val name = args.string(0, "name")
                    resolveScriptTab(provider, context, name)?.let { return@scriptFunction scriptTabValue(it) }

                    // A name `binary.tabs` advertised but that resolves to nothing
                    // has no content for this binary — Problems on a module with no
                    // parse problems, Garbage with nothing unreachable. Reporting
                    // that as an empty table keeps a loop over `binary.tabs` from
                    // throwing halfway through; only a name that was never
                    // advertised is an error.
                    val advertised = scriptTabNames(provider, context)
                    if (name in advertised) {
                        return@scriptFunction scriptTabValue(TableTabDescriptor(name, emptyList(), 0))
                    }
                    throw IllegalArgumentException(
                        "binary.tab(\"$name\") — no such tab. Available: " + advertised.joinToString(", ")
                    )
                }
            },
            "raw" to { ScriptValue.Host(provider) },
        ),
        hostFallback = provider,
    )
}

/**
 * Last path segment of [value], for either separator.
 *
 * Both are handled unconditionally rather than per platform: a path can reach a
 * script from a host running on the other kind of system (an MCP client, a
 * capture taken elsewhere), and a name with no separator is returned unchanged.
 */
internal fun scriptFileBaseName(value: String): String =
    value.substringAfterLast('/').substringAfterLast('\\')

/**
 * Directory containing [path], without a trailing separator, or null when [path]
 * names no directory at all.
 *
 * A root keeps its separator, because dropping it would turn `/app.wasm` into the
 * empty string and `C:\app.wasm` into the drive-relative `C:` — which names a
 * different place.
 */
internal fun scriptFileDirectory(path: String): String? {
    val separator = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    if (separator < 0) return null
    if (separator == 0) return path.substring(0, 1)
    if (separator == 2 && path[1] == ':') return path.substring(0, 3)
    return path.substring(0, separator)
}

/** Names of every table tab the product can render for [provider]. */
fun scriptTabNames(provider: BinaryProvider, context: TableTabsContext): List<String> {
    val names = LinkedHashSet<String>()
    for (tabsProvider in TableTabsProviderRegistry.providers) {
        val advertised = tabsProvider.availableTabNames(provider, context)
        if (advertised != null) {
            names += advertised.first
            names += advertised.second
            continue
        }
        // The provider cannot answer cheaply; fall back to full resolution as
        // TableTabsProvider.availableTabNames documents.
        tabsProvider.tableTabs(provider, context).forEach { names += it.name }
        tabsProvider.groupedTabs(provider, context).forEach { names += it.name }
    }
    return names.toList()
}

private fun resolveScriptTab(
    provider: BinaryProvider,
    context: TableTabsContext,
    name: String,
): TableTabDescriptor? {
    for (tabsProvider in TableTabsProviderRegistry.providers) {
        tabsProvider.singleTab(name, provider, context)?.let { return it }
    }
    // Tabs nested inside a grouped tab are addressable by their own name too.
    for (tabsProvider in TableTabsProviderRegistry.providers) {
        for (group in tabsProvider.groupedTabs(provider, context)) {
            group.innerTabs.firstOrNull { it.name == name }?.let { return it }
        }
    }
    return null
}

/** Wraps one table so a script can read its columns and cells. */
internal fun scriptTabValue(tab: TableTabDescriptor): ScriptValue.Obj {
    val columnNames = tab.columns.map { it.name }

    fun cellAt(rowIndex: Int, columnIndex: Int, function: String): CellValue<*> {
        if (rowIndex < 0 || rowIndex >= tab.rowCount) {
            throw IllegalArgumentException(
                "$function — row $rowIndex is out of bounds, '${tab.name}' has ${tab.rowCount} row(s)"
            )
        }
        if (columnIndex < 0 || columnIndex >= tab.columns.size) {
            throw IllegalArgumentException(
                "$function — column $columnIndex is out of bounds, '${tab.name}' has " +
                    "${tab.columns.size} column(s): ${columnNames.joinToString(", ")}"
            )
        }
        return tab.columns[columnIndex].cellProvider(rowIndex)
    }

    return ScriptValue.Obj(
        linkedMapOf(
            "name" to { ScriptValue.Str(tab.name) },
            "columns" to { ScriptValue.strings(columnNames) },
            "rowCount" to { ScriptValue.Num(tab.rowCount) },
            "columnIndex" to {
                scriptFunction("tab.columnIndex(name)", minArgs = 1) { args ->
                    ScriptValue.Num(columnNames.indexOf(args.string(0, "name")))
                }
            },
            "cell" to {
                scriptFunction("tab.cell(row, column)", minArgs = 2) { args ->
                    val cell = cellAt(
                        args.int(0, "row"),
                        args.int(1, "column"),
                        "tab.cell(row, column)",
                    )
                    ScriptValue.Str(cell.representation)
                }
            },
            "value" to {
                scriptFunction("tab.value(row, column)", minArgs = 2) { args ->
                    val cell = cellAt(
                        args.int(0, "row"),
                        args.int(1, "column"),
                        "tab.value(row, column)",
                    )
                    cellUnderlyingValue(cell)
                }
            },
            "row" to {
                scriptFunction("tab.row(row)", minArgs = 1) { args ->
                    val rowIndex = args.int(0, "row")
                    ScriptValue.Arr(tab.columns.size) { columnIndex ->
                        ScriptValue.Str(cellAt(rowIndex, columnIndex, "tab.row(row)").representation)
                    }
                }
            },
            "rows" to {
                scriptFunction("tab.rows()") {
                    ScriptValue.Arr(tab.rowCount) { rowIndex ->
                        ScriptValue.Arr(tab.columns.size) { columnIndex ->
                            ScriptValue.Str(tab.columns[columnIndex].cellProvider(rowIndex).representation)
                        }
                    }
                }
            },
        )
    )
}

/**
 * Converts a cell's sort value, but only when it is already a primitive.
 *
 * A [CellValue] can carry an arbitrary host object as its sort key; handing that
 * to the script would leak an internal type into a surface that is supposed to
 * be stable, so anything non-primitive degrades to the displayed text.
 */
private fun cellUnderlyingValue(cell: CellValue<*>): ScriptValue = when (val value = cell.value) {
    null -> ScriptValue.Null
    is String -> ScriptValue.Str(value)
    is Number -> ScriptValue.Num(value)
    is Boolean -> ScriptValue.Bool(value)
    else -> ScriptValue.Str(cell.representation)
}

private fun structureRangeValue(range: StructureRange): ScriptValue = ScriptValue.Obj(
    linkedMapOf(
        "name" to { ScriptValue.Str(range.label) },
        "offset" to { ScriptValue.Num(range.offset) },
        "size" to { ScriptValue.Num(range.size) },
        "tab" to { ScriptValue.of(range.tabName) },
    )
)

private fun stringRecordValue(record: BinaryStringRecord): ScriptValue = ScriptValue.Obj(
    linkedMapOf(
        "value" to { ScriptValue.Str(record.value) },
        "offset" to { ScriptValue.Num(record.offset) },
        "size" to { ScriptValue.Num(record.size) },
        "index" to { ScriptValue.Num(record.index) },
        "tag" to { ScriptValue.of(record.tag) },
    )
)

private fun sizeNodeValue(node: BinarySizeNode): ScriptValue = ScriptValue.Obj(
    linkedMapOf(
        "name" to { ScriptValue.Str(node.name) },
        "size" to { ScriptValue.Num(node.size) },
        "offset" to { ScriptValue.Num(node.offset) },
        "children" to { ScriptValue.array(node.children, ::sizeNodeValue) },
        "percentage" to {
            scriptFunction("sizeAnalysis.percentage(parentSize)", minArgs = 1) { args ->
                ScriptValue.Num(node.percentage(args.int(0, "parentSize")))
            }
        },
    )
)

/**
 * Byte-level accessors for the loaded binary.
 *
 * Holds a single [CommonByteBuffer] for the lifetime of a script run: the
 * provider hands out a fresh cursor on every `bytes` access, so re-reading it
 * per call would silently reset the cursor that the back-compatible zero-argument
 * `get()` advances.
 */
private class ScriptBytes(provider: BinaryProvider) {
    val buffer: CommonByteBuffer by lazy { provider.bytes }

    fun toScriptValue(): ScriptValue.Obj = ScriptValue.Obj(
        linkedMapOf(
            "size" to { ScriptValue.Num(buffer.size) },
            "lastIndex" to { ScriptValue.Num(buffer.lastIndex) },
            "indices" to { indicesValue(buffer.size) },
            "get" to {
                // Pre-v1 accessor, kept as-is: no argument reads at the cursor
                // and advances it, one argument reads absolutely.
                scriptFunction("binary.bytes.get(index)", minArgs = 0, maxArgs = 1) { args ->
                    if (args.size == 0) {
                        ScriptValue.Num(buffer.get().unsigned())
                    } else {
                        ScriptValue.Num(byteAt(args.int(0, "index"), "binary.bytes.get(index)"))
                    }
                }
            },
            "at" to {
                scriptFunction("binary.bytes.at(index)", minArgs = 1) { args ->
                    ScriptValue.Num(byteAt(args.int(0, "index"), "binary.bytes.at(index)"))
                }
            },
            "slice" to {
                scriptFunction("binary.bytes.slice(offset, length)", minArgs = 2) { args ->
                    val (offset, length) = boundedRange(args, "binary.bytes.slice(offset, length)")
                    ScriptValue.Arr(length) { ScriptValue.Num(byteAt(offset + it, "binary.bytes.slice")) }
                }
            },
            "hex" to {
                scriptFunction("binary.bytes.hex(offset, length)", minArgs = 2) { args ->
                    val (offset, length) = boundedRange(args, "binary.bytes.hex(offset, length)")
                    ScriptValue.Str(
                        (0 until length).joinToString(" ") { index ->
                            byteAt(offset + index, "binary.bytes.hex")
                                .toString(16)
                                .padStart(2, '0')
                        }
                    )
                }
            },
            "ascii" to {
                scriptFunction("binary.bytes.ascii(offset, length)", minArgs = 2) { args ->
                    val (offset, length) = boundedRange(args, "binary.bytes.ascii(offset, length)")
                    ScriptValue.Str(
                        buildString(length) {
                            for (index in 0 until length) {
                                val byte = byteAt(offset + index, "binary.bytes.ascii")
                                append(if (byte in PRINTABLE_MIN..PRINTABLE_MAX) byte.toChar() else '.')
                            }
                        }
                    )
                }
            },
            "u8" to {
                scriptFunction("binary.bytes.u8(offset)", minArgs = 1) { args ->
                    ScriptValue.Num(byteAt(args.int(0, "offset"), "binary.bytes.u8(offset)"))
                }
            },
            "u16" to {
                scriptFunction("binary.bytes.u16(offset, littleEndian)", minArgs = 1, maxArgs = 2) { args ->
                    ScriptValue.Num(
                        readUnsigned(
                            offset = args.int(0, "offset"),
                            width = 2,
                            littleEndian = args.booleanOr(1, default = true),
                            function = "binary.bytes.u16(offset, littleEndian)",
                        )
                    )
                }
            },
            "u32" to {
                scriptFunction("binary.bytes.u32(offset, littleEndian)", minArgs = 1, maxArgs = 2) { args ->
                    ScriptValue.Num(
                        readUnsigned(
                            offset = args.int(0, "offset"),
                            width = 4,
                            littleEndian = args.booleanOr(1, default = true),
                            function = "binary.bytes.u32(offset, littleEndian)",
                        )
                    )
                }
            },
            "indexOf" to {
                scriptFunction("binary.bytes.indexOf(pattern, from)", minArgs = 1, maxArgs = 2) { args ->
                    val pattern = args.bytePattern(0, "pattern")
                    val from = args.intOr(1, "from", default = 0)
                    ScriptValue.Num(indexOf(pattern, from))
                }
            },
        )
    )

    private fun byteAt(index: Int, function: String): Int {
        if (index < 0 || index >= buffer.size) {
            throw IllegalArgumentException(
                "$function — offset $index is outside the binary (0..${buffer.lastIndex})"
            )
        }
        return buffer[index].unsigned()
    }

    private fun boundedRange(args: ScriptArgs, function: String): Pair<Int, Int> {
        val offset = args.int(0, "offset")
        val length = args.int(1, "length")
        if (length < 0) {
            throw IllegalArgumentException("$function — length must not be negative")
        }
        if (length > MAX_BYTES_PER_CALL) {
            throw IllegalArgumentException(
                "$function — length $length exceeds the per-call limit of $MAX_BYTES_PER_CALL bytes; " +
                    "read the binary in chunks"
            )
        }
        if (offset < 0 || offset + length > buffer.size) {
            throw IllegalArgumentException(
                "$function — range $offset..${offset + length} is outside the binary (0..${buffer.size})"
            )
        }
        return offset to length
    }

    private fun readUnsigned(offset: Int, width: Int, littleEndian: Boolean, function: String): Long {
        if (offset < 0 || offset + width > buffer.size) {
            throw IllegalArgumentException(
                "$function — reading $width byte(s) at $offset goes past the end of the binary " +
                    "(size ${buffer.size})"
            )
        }
        var result = 0L
        for (index in 0 until width) {
            val byte = buffer[offset + index].unsigned().toLong()
            val shift = if (littleEndian) index else width - 1 - index
            result = result or (byte shl (shift * 8))
        }
        return result
    }

    private fun indexOf(pattern: ByteArray, from: Int): Int {
        if (pattern.isEmpty()) return -1
        val start = maxOf(from, 0)
        val last = buffer.size - pattern.size
        var candidate = start
        while (candidate <= last) {
            var matched = 0
            while (matched < pattern.size && buffer[candidate + matched] == pattern[matched]) {
                matched++
            }
            if (matched == pattern.size) return candidate
            candidate++
        }
        return -1
    }

    private fun indicesValue(size: Int): ScriptValue = ScriptValue.Obj(
        linkedMapOf(
            "first" to { ScriptValue.Num(0) },
            "last" to { ScriptValue.Num(size - 1) },
            "start" to { ScriptValue.Num(0) },
            "endInclusive" to { ScriptValue.Num(size - 1) },
            "endExclusive" to { ScriptValue.Num(size) },
            "step" to { ScriptValue.Num(1) },
        )
    )

    private companion object {
        /**
         * Cap on bytes materialized by a single `slice`/`hex`/`ascii` call. The
         * script output limits only bound what is *printed*; without this a
         * single `hex(0, binary.size)` on a large binary would build a string
         * hundreds of megabytes long before any limit applied.
         */
        const val MAX_BYTES_PER_CALL = 1 shl 20

        const val PRINTABLE_MIN = 0x20
        const val PRINTABLE_MAX = 0x7E
    }
}

private fun Byte.unsigned(): Int = toInt() and 0xFF
