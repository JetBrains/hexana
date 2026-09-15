package org.jetbrains.hexana.shared.scripting

/**
 * One member of the scripting API.
 *
 * @param name member name as written in a script
 * @param signature the member rendered as valid TypeScript interface text, e.g.
 *   `size: number` or `hex(offset: number, length: number): string`
 * @param summary one-sentence description shown in the reference and the dialogs
 * @param example optional single-line snippet
 * @param displayPath how the member is written in the compact dialog listing;
 *   defaults to `binary.<name>`
 */
class ScriptApiEntry(
    val name: String,
    val signature: String,
    val summary: String,
    val example: String? = null,
    val displayPath: String? = null,
)

/**
 * A named object in the scripting API, rendered as one TypeScript interface.
 *
 * @param shape stable key used by tests to pair a runtime object with its docs
 * @param typeName TypeScript interface name
 */
class ScriptApiShape(
    val shape: String,
    val typeName: String,
    val summary: String,
    val members: List<ScriptApiEntry>,
) {
    val memberNames: Set<String> get() = members.map { it.name }.toSet()
}

/**
 * A bundled example script, offered by the Add Script Tab dialogs.
 *
 * @param fileName file under `examples/scripting/` in the repository, shipped in
 *   both plugins
 */
class ScriptApiExample(
    val fileName: String,
    val title: String,
    val summary: String,
)

/**
 * The single source of truth for the scripting API documentation.
 *
 * The reference, the bundled `.d.ts`, the "Available APIs" section of the
 * Add Script Tab dialogs in both plugins, the starter template, and the
 * `run_binary_script` tool guidance are all rendered from here. Previously each
 * of those was hand-written separately and they had already drifted apart; a
 * test asserts that [shapes] matches what [scriptBinaryApi] actually exposes, so
 * they cannot drift again.
 */
object ScriptApiReference {

    const val BINARY_SHAPE: String = "binary"
    const val BYTES_SHAPE: String = "bytes"
    const val INDICES_SHAPE: String = "indices"
    const val STRUCTURE_SHAPE: String = "structureRange"
    const val STRING_SHAPE: String = "string"
    const val SIZE_NODE_SHAPE: String = "sizeNode"
    const val TAB_SHAPE: String = "tab"

    /** The global functions available to a script. */
    val globals: List<ScriptApiEntry> = listOf(
        ScriptApiEntry(
            name = "print",
            signature = "print(...values: any[]): void",
            summary = "Appends one line to the tab output, joining the arguments with a space.",
            example = "print(\"format:\", binary.format);",
            displayPath = "print(...values)",
        ),
        ScriptApiEntry(
            name = "table",
            signature = "table(...columns: any[]): void",
            summary = "Starts a table and declares its column headers. Text printed before or " +
                "after it stays text, so a script can narrate between tables.",
            example = "table(\"Section\", \"Offset\", \"Size\");",
            displayPath = "table(...columns)",
        ),
        ScriptApiEntry(
            name = "row",
            signature = "row(...cells: any[]): void",
            summary = "Appends one row to the current table. Cells are escaped, and rows shorter " +
                "than the header are padded. Requires a preceding table() call.",
            example = "row(section.name, section.offset, section.size);",
            displayPath = "row(...cells)",
        ),
        ScriptApiEntry(
            name = "binary",
            signature = "const binary: HexanaBinary",
            summary = "The binary currently open in the editor.",
            example = "print(binary.formatName, binary.size);",
        ),
        ScriptApiEntry(
            name = "open",
            signature = "open(path: string): HexanaBinary",
            summary = "Opens another binary from a local file path and returns the same shape as " +
                "`binary`, so a script can compare two files. The format is detected from the " +
                "content. Needs a host with file access: the IntelliJ IDEA script tab, the " +
                "run-script CLI, and the MCP tool have it, the VS Code webview and the web app " +
                "do not and report that. Where allowed roots are configured, the path must be " +
                "inside one.",
            example = "const before = open(\"/tmp/before.wasm\"); print(binary.size - before.size);",
            displayPath = "open(path)",
        ),
    )

    val shapes: List<ScriptApiShape> = listOf(
        ScriptApiShape(
            shape = BINARY_SHAPE,
            typeName = "HexanaBinary",
            summary = "The binary open in the editor. Every member below works for every " +
                "supported format; format-specific data is reachable through `raw`.",
            members = listOf(
                ScriptApiEntry(
                    "format",
                    "readonly format: string",
                    "Stable format id: \"wasm\", \"wasm-component\", \"elf\", \"macho\", " +
                        "\"macho-universal\", \"pe\", \"coff\", \"archive\", \"zip\", \"tar\", " +
                        "\"class\", \"dex\", \"arsc\", \"pcap\", \"pcapng\", \"parquet\", " +
                        "\"arrow\", \"protobuf\" or \"binary\". Safe to branch on.",
                    "if (binary.format === \"elf\") print(binary.raw.header.machine);",
                ),
                ScriptApiEntry(
                    "formatName",
                    "readonly formatName: string",
                    "Human-readable format name as shown in the editor, e.g. \"Core Wasm\" or \"ELF64\".",
                ),
                ScriptApiEntry(
                    "name",
                    "readonly name: string | null",
                    "File name without its directory, when the host knows it.",
                ),
                ScriptApiEntry(
                    "path",
                    "readonly path: string | null",
                    "Full path of the file, or null when there is no file behind the binary — a " +
                        "ZIP entry, a module opened from a table, anything in a browser.",
                    "if (binary.path) print(binary.path);",
                ),
                ScriptApiEntry(
                    "dir",
                    "readonly dir: string | null",
                    "Directory containing the file, without a trailing separator, or null when " +
                        "`path` is null. Join with \"/\", which works on every platform Hexana " +
                        "runs on, to reach a file next to this one.",
                    "const map = open(binary.dir + \"/zephyr.map\");",
                ),
                ScriptApiEntry(
                    "size",
                    "readonly size: number",
                    "File size in bytes.",
                ),
                ScriptApiEntry(
                    "bytes",
                    "readonly bytes: HexanaBytes",
                    "Raw byte access.",
                ),
                ScriptApiEntry(
                    "structure",
                    "readonly structure: HexanaStructureRange[]",
                    "Top-level regions of the binary — Wasm sections, ELF sections, Mach-O " +
                        "segments, and so on — in file order.",
                    "for (const r of binary.structure) print(r.name, r.size);",
                ),
                ScriptApiEntry(
                    "strings",
                    "readonly strings: HexanaString[]",
                    "Strings extracted from the binary.",
                ),
                ScriptApiEntry(
                    "sizeAnalysis",
                    "readonly sizeAnalysis: HexanaSizeNode | null",
                    "Hierarchical size breakdown, or null for formats without size profiling.",
                ),
                ScriptApiEntry(
                    "tabs",
                    "readonly tabs: string[]",
                    "Names of every table Hexana can show for this binary. Whatever tab you can " +
                        "see in the editor, you can read here.",
                    "print(binary.tabs.join(\", \"));",
                ),
                ScriptApiEntry(
                    "tab",
                    "tab(name: string): HexanaTab",
                    "Reads one table by name, using the same column names the editor shows. A name " +
                        "from `tabs` that has no content for this binary reads as a table with no " +
                        "rows; only a name that is not in `tabs` at all is an error.",
                    "const exports = binary.tab(\"Exports\");",
                    displayPath = "binary.tab(name)",
                ),
                ScriptApiEntry(
                    "raw",
                    "readonly raw: any",
                    "Escape hatch: the format-specific provider, unfiltered. Its shape depends on " +
                        "the format — see hexana-scripting-raw.d.ts. Members not listed above are " +
                        "also resolved against it directly, so older scripts keep working.",
                    "if (binary.format === \"elf\") print(binary.raw.programHeaders.length);",
                ),
            ),
        ),
        ScriptApiShape(
            shape = BYTES_SHAPE,
            typeName = "HexanaBytes",
            summary = "Raw byte access. Offsets are absolute file offsets. Reads outside the " +
                "binary throw rather than returning a placeholder.",
            members = listOf(
                ScriptApiEntry("size", "readonly size: number", "Number of bytes in the binary."),
                ScriptApiEntry("lastIndex", "readonly lastIndex: number", "Largest valid offset, i.e. `size - 1`."),
                ScriptApiEntry("indices", "readonly indices: HexanaIndices", "Bounds of the valid offset range."),
                ScriptApiEntry(
                    "get",
                    "get(index?: number): number",
                    "Reads one byte (0-255). With an argument reads at that offset; without one " +
                        "reads at the internal cursor and advances it. Prefer at().",
                ),
                ScriptApiEntry(
                    "at",
                    "at(index: number): number",
                    "Reads one byte (0-255) at an absolute offset.",
                    "print(binary.bytes.at(0));",
                ),
                ScriptApiEntry(
                    "slice",
                    "slice(offset: number, length: number): number[]",
                    "Reads a range of bytes as numbers. At most 1 MiB per call.",
                ),
                ScriptApiEntry(
                    "hex",
                    "hex(offset: number, length: number): string",
                    "Space-separated lowercase hex dump of a range. At most 1 MiB per call.",
                    "print(binary.bytes.hex(0, 8));",
                ),
                ScriptApiEntry(
                    "ascii",
                    "ascii(offset: number, length: number): string",
                    "Range as text, with non-printable bytes shown as '.'. At most 1 MiB per call.",
                ),
                ScriptApiEntry("u8", "u8(offset: number): number", "Reads an unsigned 8-bit value."),
                ScriptApiEntry(
                    "u16",
                    "u16(offset: number, littleEndian?: boolean): number",
                    "Reads an unsigned 16-bit value; little-endian by default.",
                ),
                ScriptApiEntry(
                    "u32",
                    "u32(offset: number, littleEndian?: boolean): number",
                    "Reads an unsigned 32-bit value; little-endian by default.",
                ),
                ScriptApiEntry(
                    "indexOf",
                    "indexOf(pattern: string | number[], from?: number): number",
                    "Offset of the first occurrence of a byte pattern, or -1.",
                    "print(binary.bytes.indexOf(\"\\0asm\"));",
                ),
            ),
        ),
        ScriptApiShape(
            shape = INDICES_SHAPE,
            typeName = "HexanaIndices",
            summary = "Bounds of the valid byte-offset range.",
            members = listOf(
                ScriptApiEntry("first", "readonly first: number", "First valid offset, always 0."),
                ScriptApiEntry("last", "readonly last: number", "Last valid offset."),
                ScriptApiEntry("start", "readonly start: number", "Alias of first."),
                ScriptApiEntry("endInclusive", "readonly endInclusive: number", "Alias of last."),
                ScriptApiEntry("endExclusive", "readonly endExclusive: number", "One past the last valid offset."),
                ScriptApiEntry("step", "readonly step: number", "Always 1."),
            ),
        ),
        ScriptApiShape(
            shape = STRUCTURE_SHAPE,
            typeName = "HexanaStructureRange",
            summary = "A top-level region of the binary.",
            members = listOf(
                ScriptApiEntry("name", "readonly name: string", "Region label, e.g. a section or segment name."),
                ScriptApiEntry("offset", "readonly offset: number", "Byte offset of the region."),
                ScriptApiEntry("size", "readonly size: number", "Region size in bytes."),
                ScriptApiEntry(
                    "tab",
                    "readonly tab: string | null",
                    "Name of the editor tab that shows this region, when there is one.",
                ),
            ),
        ),
        ScriptApiShape(
            shape = STRING_SHAPE,
            typeName = "HexanaString",
            summary = "A string extracted from the binary.",
            members = listOf(
                ScriptApiEntry("value", "readonly value: string", "The string itself."),
                ScriptApiEntry("offset", "readonly offset: number", "Byte offset, or -1 when unknown."),
                ScriptApiEntry("size", "readonly size: number", "Encoded size in bytes, or -1 when unknown."),
                ScriptApiEntry("index", "readonly index: number", "Position within its source table, or -1."),
                ScriptApiEntry("tag", "readonly tag: string | null", "Origin of the string, e.g. a section name."),
            ),
        ),
        ScriptApiShape(
            shape = SIZE_NODE_SHAPE,
            typeName = "HexanaSizeNode",
            summary = "A node of the size breakdown tree.",
            members = listOf(
                ScriptApiEntry("name", "readonly name: string", "Node label."),
                ScriptApiEntry("size", "readonly size: number", "Total size of this node in bytes."),
                ScriptApiEntry("offset", "readonly offset: number", "Byte offset of this node."),
                ScriptApiEntry("children", "readonly children: HexanaSizeNode[]", "Child nodes; empty for a leaf."),
                ScriptApiEntry(
                    "percentage",
                    "percentage(parentSize: number): number",
                    "This node's size as a percentage of the given total.",
                ),
            ),
        ),
        ScriptApiShape(
            shape = TAB_SHAPE,
            typeName = "HexanaTab",
            summary = "One table from the editor, resolved by binary.tab(name). Rows are read " +
                "on demand, so a table with a million rows costs nothing until you touch it.",
            members = listOf(
                ScriptApiEntry("name", "readonly name: string", "Tab name."),
                ScriptApiEntry("columns", "readonly columns: string[]", "Column headers, in order."),
                ScriptApiEntry("rowCount", "readonly rowCount: number", "Number of rows."),
                ScriptApiEntry(
                    "columnIndex",
                    "columnIndex(name: string): number",
                    "Index of a column by header, or -1.",
                ),
                ScriptApiEntry(
                    "cell",
                    "cell(row: number, column: number): string",
                    "Cell text, exactly as the editor displays it.",
                    "print(t.cell(0, t.columnIndex(\"Name\")));",
                ),
                ScriptApiEntry(
                    "value",
                    "value(row: number, column: number): string | number | boolean | null",
                    "Underlying cell value when it is a primitive — useful for sorting and " +
                        "arithmetic — otherwise the displayed text.",
                ),
                ScriptApiEntry("row", "row(row: number): string[]", "One row as an array of cell texts."),
                ScriptApiEntry("rows", "rows(): string[][]", "All rows as arrays of cell texts."),
            ),
        ),
    )

    /**
     * Runnable examples shipped with both plugins, offered next to the starter
     * template so a user never has to begin from an empty file.
     */
    val examples: List<ScriptApiExample> = listOf(
        ScriptApiExample(
            fileName = "regions-by-size.js",
            title = "Regions by size",
            summary = "Top-level regions sorted by size, with each one's share of the file.",
        ),
        ScriptApiExample(
            fileName = "string-report.js",
            title = "String report",
            summary = "Longest strings and a count per source tag.",
        ),
        ScriptApiExample(
            fileName = "dump-tab.js",
            title = "Dump an editor tab",
            summary = "Reads any table the editor shows and re-emits it as a script table.",
        ),
        ScriptApiExample(
            fileName = "header-hexdump.js",
            title = "Header hex dump",
            summary = "Hex and ASCII dump of the first bytes, plus a magic-number check.",
        ),
        ScriptApiExample(
            fileName = "compare-binaries.js",
            title = "Compare two binaries",
            summary = "Diffs the open file against another one you point `open(path)` at: " +
                "sizes, per-region deltas, and tab row counts.",
        ),
    )

    /** File name suggested for a script created from [starterTemplate]. */
    const val STARTER_FILE_NAME: String = "hexana-script.js"

    /**
     * Name of the type-definition sidecar written next to a script so that an
     * editor resolves `binary`, `print`, `table` and `row`.
     *
     * The content comes from [dts] rather than from a bundled resource, so the
     * types a user gets completion against are the same ones this reference
     * documents, with nothing to keep in sync or package.
     */
    const val TYPES_FILE_NAME: String = "hexana-scripting.d.ts"

    /** Name of the config sidecar that points a JavaScript editor at [TYPES_FILE_NAME]. */
    const val JS_CONFIG_FILE_NAME: String = "jsconfig.json"

    /**
     * Content of the [JS_CONFIG_FILE_NAME] sidecar.
     *
     * A standalone `.js` file gets no automatic association with a sibling
     * `.d.ts`, so VS Code needs this to include the types in the script's
     * project. `checkJs` stays off: the point is completion and navigation, not
     * type errors on a scratch script.
     */
    fun jsConfig(): String = """
        {
          "compilerOptions": {
            "target": "ES2020",
            "lib": ["ES2020"],
            "checkJs": false,
            "noEmit": true
          },
          "include": ["*.js", "$TYPES_FILE_NAME"]
        }
    """.trimIndent() + "\n"

    fun shape(shape: String): ScriptApiShape =
        shapes.firstOrNull { it.shape == shape } ?: error("Unknown scripting API shape '$shape'")

    /** The handful of members worth showing directly in the Add Script Tab dialogs. */
    fun highlights(): List<ScriptApiEntry> {
        val binary = shape(BINARY_SHAPE)
        return globals.filter { it.name != "binary" } +
            listOf("format", "size", "path", "structure", "strings", "tabs", "tab", "raw")
                .map { name -> binary.members.first { it.name == name } }
    }

    /** Compact `path — summary` lines, for the dialogs and the tool guidance. */
    fun summaryLines(): List<String> = highlights().map { entry ->
        "${entry.displayPath ?: "binary.${entry.name}"} — ${entry.summary}"
    }

    /** The runnable starter script offered by "New script from template". */
    fun starterTemplate(): String = STARTER_TEMPLATE

    /** Renders the curated `hexana-scripting.d.ts`. */
    fun dts(): String = buildString {
        appendLine("// Hexana scripting API — the globals available in a custom script tab.")
        appendLine("//")
        appendLine("// This file is generated from ScriptApiReference.kt. Everything here behaves")
        appendLine("// identically in IntelliJ IDEA, in VS Code, and in the `run-script` CLI, except")
        appendLine("// `open(path)`: it needs local file access, so it reports that it is unavailable")
        appendLine("// in the VS Code webview and the web app. Each member says what it needs.")
        appendLine("// For the format-specific shape of `binary.raw`, see hexana-scripting-raw.d.ts.")
        appendLine("//")
        appendLine("// Output layout: a script tab renders text and tables in the order the script")
        appendLine("// produced them. `print(...)` writes text; `table(...)` starts a table and each")
        appendLine("// following `row(...)` adds a row to it. Printing text again ends the table, so")
        appendLine("// a script can freely alternate between prose and tables.")
        appendLine()

        for (entry in globals) {
            appendDoc(entry)
            if (entry.signature.startsWith("const ")) {
                appendLine("declare ${entry.signature};")
            } else {
                appendLine("declare function ${entry.signature};")
            }
            appendLine()
        }

        for (shape in shapes) {
            appendLine("/**")
            appendWrapped(shape.summary)
            appendLine(" */")
            appendLine("interface ${shape.typeName} {")
            for ((index, member) in shape.members.withIndex()) {
                if (index > 0) appendLine()
                appendDoc(member, indent = "  ")
                appendLine("  ${member.signature};")
            }
            appendLine("}")
            appendLine()
        }
    }

    private fun StringBuilder.appendDoc(entry: ScriptApiEntry, indent: String = "") {
        appendLine("$indent/**")
        appendWrapped(entry.summary, indent)
        entry.example?.let {
            appendLine("$indent *")
            appendLine("$indent * @example $it")
        }
        appendLine("$indent */")
    }

    private fun StringBuilder.appendWrapped(text: String, indent: String = "") {
        var line = StringBuilder()
        for (word in text.split(' ')) {
            if (line.isNotEmpty() && line.length + word.length + 1 > DOC_WIDTH) {
                appendLine("$indent * $line")
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) appendLine("$indent * $line")
    }

    private const val DOC_WIDTH = 72

    private val STARTER_TEMPLATE = """
        // Hexana custom script tab.
        //
        // Runs against the binary open in the editor. `print` adds a line of text;
        // `table` + `row` render a searchable table. Save the file and press the
        // refresh button in the tab to re-run it.
        //
        // Full API: hexana-scripting.d.ts (linked from the Add Script Tab dialog).

        // `binary.name` is null when the host does not know the file name.
        print((binary.name || "binary") + " — " + binary.formatName + ", " + binary.size + " bytes");
        print("First bytes: " + binary.bytes.hex(0, 8));
        print("");

        // Everything the editor can show for this binary is readable by name:
        print("Tabs: " + binary.tabs.join(", "));
        print("");

        // table() + row() render a table; print() around them stays text, so a
        // script can narrate between tables.
        table("Region", "Offset", "Size", "% of file");
        for (const region of binary.structure) {
            const share = (region.size * 100 / binary.size).toFixed(2);
            row(region.name, "0x" + region.offset.toString(16), region.size, share);
        }

        // Try next:
        //   const t = binary.tab(binary.tabs[0]);
        //   for (let i = 0; i < Math.min(t.rowCount, 20); i++) print(t.row(i).join(" | "));
        //
        //   binary.strings.filter(s => s.value.length > 12).forEach(s => print(s.value));
        //
        //   binary.raw   // format-specific data, see hexana-scripting-raw.d.ts
        //
        //   open("/path/to/other.wasm")   // another binary, same members as `binary`
    """.trimIndent() + "\n"
}
