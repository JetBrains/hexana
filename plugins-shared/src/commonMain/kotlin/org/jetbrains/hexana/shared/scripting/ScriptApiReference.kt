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
    val deprecated: String? = null,
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
    const val CHECK_REPORT_SHAPE: String = "checkReport"
    const val CHECK_SPEC_SHAPE: String = "checkSpec"

    /** The global functions available to a script. */
    val globals: List<ScriptApiEntry> = listOf(
        ScriptApiEntry(
            name = "print",
            signature = "print(...values: any[]): void",
            summary = "Appends one line to the tab output, joining the arguments with a space.",
            example = "print(\"format:\", binary.format);",
            displayPath = "print(...values)",
            deprecated = "Use console.log, console.error, or console.debug for diagnostics. " +
                "Use table/row or hexana.defineSlice for tabular data.",
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
                "content. Needs a host with local file access: every editor has it; the web app " +
                "does not and reports that. Where allowed roots are configured, the path must be " +
                "inside one. In a VS Code script tab, call it at the top level of the script: the " +
                "script pauses there while the file is read, which it cannot do inside a " +
                "function or a callback.",
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
                        "\"class\", \"dex\", \"arsc\", \"dotnet-resources\", \"pdb\", \"portable-pdb\", \"pcap\", \"pcapng\", \"parquet\", " +
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
                    "Full path of the file, or null when there is no file behind the binary: a " +
                        "ZIP entry, a module opened from a table, anything in a browser.",
                    "if (binary.path) print(binary.path);",
                ),
                ScriptApiEntry(
                    "dir",
                    "readonly dir: string | null",
                    "Directory containing the file, or null when `path` is null. It has no " +
                        "trailing separator, except for a root such as `/` or `C:\\`, which keeps " +
                        "one because dropping it would name a different place. Join with \"/\", " +
                        "which works on every platform Hexana runs on, to reach a file next to " +
                        "this one.",
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
                    "Top-level regions of the binary (Wasm sections, ELF sections, Mach-O " +
                        "segments, and so on) in file order.",
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
                        "the format: see hexana-scripting-raw.d.ts. Members not listed above are " +
                        "also resolved against it directly, so older scripts keep working.",
                    "if (binary.format === \"elf\") print(binary.raw.programHeaders.length);",
                ),
                ScriptApiEntry(
                    "functions",
                    "functions(options?: FunctionsQuery): FunctionsPage",
                    "Paginated, filterable list of functions (Wasm only; other formats return an empty page). " +
                        "Options: name (substring, case-insensitive), unnamed (boolean, only unresolved names), " +
                        "minBodySize (number, code functions only), offset (number), limit (number, default 1000). " +
                        "Returns { items, total, nextOffset? }.",
                    "const page = binary.functions({ name: \"malloc\", limit: 50 });",
                    displayPath = "binary.functions(options?)",
                ),
                ScriptApiEntry(
                    "imports",
                    "imports(): WasmImportEntry[]",
                    "All import entries for Wasm binaries; other formats return an empty array. " +
                        "Each entry: { module, name, kind, index }.",
                    "binary.imports().filter(i => i.kind === \"func\").forEach(i => print(i.module, i.name));",
                    displayPath = "binary.imports()",
                ),
                ScriptApiEntry(
                    "exports",
                    "exports(): WasmExportEntry[]",
                    "All export entries for Wasm binaries; other formats return an empty array. " +
                        "Each entry: { name, kind, index }.",
                    "binary.exports().filter(e => e.kind === \"func\").forEach(e => print(e.name, e.index));",
                    displayPath = "binary.exports()",
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
                    "Underlying cell value when it is a primitive (useful for sorting and " +
                        "arithmetic), otherwise the displayed text.",
                ),
                ScriptApiEntry("row", "row(row: number): string[]", "One row as an array of cell texts."),
                ScriptApiEntry("rows", "rows(): string[][]", "All rows as arrays of cell texts."),
            ),
        ),
        ScriptApiShape(
            shape = CHECK_REPORT_SHAPE,
            typeName = "CheckReport",
            summary = "Report object passed to a check's run() callback. Call fail/warn/info to " +
                "emit findings. The method's severity overrides the check's declared severity.",
            members = listOf(
                ScriptApiEntry(
                    "fail",
                    "fail(message: string, options?: CheckFindingOptions): void",
                    "Emits an error-severity finding.",
                    "report.fail(\"Import count too high\", { at: { funcidx: 0 } });",
                ),
                ScriptApiEntry(
                    "warn",
                    "warn(message: string, options?: CheckFindingOptions): void",
                    "Emits a warning-severity finding.",
                ),
                ScriptApiEntry(
                    "info",
                    "info(message: string, options?: CheckFindingOptions): void",
                    "Emits an info-severity finding.",
                ),
            ),
        ),
        ScriptApiShape(
            shape = CHECK_SPEC_SHAPE,
            typeName = "CheckSpec",
            summary = "Specification passed to hexana.defineCheck(). The id must start with \"user:\".",
            members = listOf(
                ScriptApiEntry("id", "id: string", "Unique check identifier, must start with \"user:\"."),
                ScriptApiEntry(
                    "severity",
                    "severity: CheckSeverity",
                    "Default severity: \"error\", \"warning\", or \"info\".",
                ),
                ScriptApiEntry("description", "description?: string", "Optional human-readable description."),
                ScriptApiEntry(
                    "run",
                    "run(binary: HexanaBinary, report: CheckReport): void",
                    "Called once after the collection pass to analyse the binary and emit findings.",
                    "run(binary, report) { if (binary.size > 1_000_000) report.warn(\"Large binary\"); }",
                ),
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

    /**
     * Additional declarations for the v2 scripting globals (hexana, console) and slice types.
     * These are host-bound globals not part of scriptGlobals(), so they live outside [globals] and [shapes].
     * The [dts] function appends this block after the core declarations.
     */
    val v2Declarations: String = """
        /** Column value type accepted by a slice definition. */
        type SliceColumnType = "string" | "int" | "offset" | "funcidx" | "bool";

        /** One column in a slice definition. */
        interface SliceColumn {
          /** Column header shown in the editor. */
          name: string;
          /** Data type for rendering and sorting. */
          type: SliceColumnType;
        }

        /** One user-supplied parameter in a slice definition. */
        interface SliceParam {
          /** Parameter name used as a key in the params record passed to rows(). */
          name: string;
          /** Expected value type. */
          type: SliceColumnType;
          /** Default value applied when the user does not supply one. */
          default?: string | number | boolean;
        }

        /** One row returned by a slice rows() callback. */
        interface SliceRowData {
          /** Byte offset used to highlight the row in the hex view. */
          anchor?: number;
          /** Cell values in column order. */
          cells: (string | number | boolean | null)[];
        }

        /** Return value of a slice rows() callback. */
        interface SliceRowsResult {
          /** Rows for the current page. */
          rows: SliceRowData[];
          /** Offset to pass as paging.offset for the next page, or absent when done. */
          nextOffset?: number;
          /** Total row count across all pages, when known. */
          total?: number;
        }

        /** Full specification passed to hexana.defineSlice(). */
        interface SliceSpec {
          /**
           * Unique tab name. Must start with "user:". Registering the same name twice
           * in one script is an error.
           */
          name: string;
          /** Binary format ids this slice applies to. Absent means all formats. */
          formats?: string[];
          /** User-configurable parameters shown in the tab toolbar. */
          params?: SliceParam[];
          /** Column definitions, in the order they appear in the table. */
          columns: SliceColumn[];
          /**
           * Callback that produces one page of rows. Called each time the user
           * scrolls or changes a parameter.
           */
          rows(
            binary: HexanaBinary,
            params: Record<string, unknown>,
            paging: { offset: number; limit: number },
          ): SliceRowsResult;
        }

        /** Severity for a check finding emitted by report.fail/warn/info. */
        type CheckSeverity = "error" | "warning" | "info";

        /** Options accepted by report.fail/warn/info. */
        interface CheckFindingOptions {
          /**
           * Location in the binary. Use { funcidx: number } for a WebAssembly function,
           * { offset: number, length: number } for a byte range, or a bare number for a byte offset.
           */
          at?: { funcidx: number } | { offset: number; length?: number } | number;
        }

        /**
         * Report object passed to a check's run() function. Use fail/warn/info to emit findings.
         * The severity of the method used takes precedence over the check's declared severity.
         */
        interface CheckReport {
          /** Emits an error-severity finding. */
          fail(message: string, options?: CheckFindingOptions): void;
          /** Emits a warning-severity finding. */
          warn(message: string, options?: CheckFindingOptions): void;
          /** Emits an info-severity finding. */
          info(message: string, options?: CheckFindingOptions): void;
        }

        /** Full specification passed to hexana.defineCheck(). */
        interface CheckSpec {
          /**
           * Unique check identifier. Must start with "user:". Registering the same id twice
           * in one script is an error.
           */
          id: string;
          /** Default severity for findings this check emits. */
          severity: CheckSeverity;
          /** Optional human-readable description shown in the UI. */
          description?: string;
          /**
           * Callback that analyses the binary and emits findings via the report object.
           * Called once per run, after the collection pass. If it throws, a single error
           * finding with the exception message is recorded; other checks still run.
           */
          run(binary: HexanaBinary, report: CheckReport): void;
        }

        /** Value type for a flag declared via hexana.defineFlags(). */
        type FlagType = "string" | "int" | "bool" | "path";

        /** One flag declared via hexana.defineFlags(). */
        interface FlagDeclaration {
          /** Optional single-character short form (without leading -). */
          short?: string;
          /** Long form (without leading --). Defaults to the declaration key name. */
          long?: string;
          /**
           * Value type. Use "path" for file paths: the file is opened as a binary handle
           * via hexana.inputs. Omit when using values.
           */
          type?: FlagType;
          /** Allowed string values (mutually exclusive with type). */
          values?: string[];
          /** Default value. null means no default (flag is absent when not supplied). */
          default?: string | number | boolean | null;
        }

        /** The hexana host API available in every script tab. */
        interface HexanaApi {
          /**
           * Registers a typed, paginated table tab. The name must start with "user:".
           * Registering the same name twice in one script is an error.
           */
          defineSlice(spec: SliceSpec): void;
          /**
           * Registers a binary check. The id must start with "user:". Registering the same
           * id twice in one script is an error. The run() callback is called after the
           * collection pass and receives a report object for emitting findings.
           */
          defineCheck(spec: CheckSpec): void;
          /**
           * Declares the flags this script accepts. Call before accessing hexana.flags.
           * Keys are logical flag names; values are flag declarations.
           *
           * @example
           * hexana.defineFlags({ verbose: { long: "verbose", type: "bool", default: false } });
           */
          defineFlags(declarations: Record<string, FlagDeclaration>): void;
          /** API version. Currently 2. */
          readonly api: number;
          /**
           * Returns true when the named feature is available.
           * Recognised values: "slices", "checks"; in the test-suite profile
           * (see the suite declarations below) has("suite") is true, and it is
           * false everywhere else.
           */
          has(feature: string): boolean;
          /**
           * Raw argument vector. args[0] is the script path; subsequent elements are
           * positional and flag arguments as given on the command line. In a tab context
           * args is [scriptPath].
           */
          readonly args: string[];
          /**
           * Positional file arguments as binary handles. inputs[0] is the primary binary
           * (same as the global binary). In a tab context inputs is [binary].
           */
          readonly inputs: HexanaBinary[];
          /**
           * Resolved flag values, keyed by the logical flag name declared in defineFlags().
           * Undeclared flags passed on the command line are a usage error (exit code 2).
           * In a tab context, every declared flag equals its default; undeclared flags are null.
           */
          readonly flags: Record<string, string | number | boolean | null>;
        }

        /** Diagnostics console available in every script tab. */
        interface HexanaConsole {
          /** Writes an info-level message to the diagnostics stream. */
          log(...values: unknown[]): void;
          /** Writes an error-level message to the diagnostics stream. */
          error(...values: unknown[]): void;
          /** Writes a debug-level message to the diagnostics stream. */
          debug(...values: unknown[]): void;
        }

        /** The Hexana host API. Use hexana.defineSlice() to register custom table tabs. */
        declare const hexana: HexanaApi;

        /**
         * Diagnostics console. Output here goes to a separate stream, not to the
         * tab output that print() writes. Use console.log/error/debug for
         * diagnostic messages; use table/row or hexana.defineSlice for tabular data.
         */
        declare const console: HexanaConsole;

        /** One function entry returned by binary.functions(). */
        interface WasmFunctionItem {
          /** Global function index (imports first, then code functions). */
          funcidx: number;
          /** Resolved name from the name section, or null when unnamed. */
          name: string | null;
          /** "import" for imported functions, "code" for locally defined ones. */
          source: "import" | "code";
          /** Import module name (imports only). */
          importModule?: string;
          /** Import field name (imports only). */
          importName?: string;
          /** Byte offset of the code body in the file (code functions only). */
          codeOffset?: number;
          /** Byte size of the code body (code functions only). */
          codeSize?: number;
        }

        /** Paginated result returned by binary.functions(). */
        interface FunctionsPage {
          /** Functions in the current page. */
          items: WasmFunctionItem[];
          /** Total number of functions matching the query across all pages. */
          total: number;
          /** Pass as offset to get the next page; absent when this is the last page. */
          nextOffset?: number;
        }

        /** Options accepted by binary.functions(). All fields are optional. */
        interface FunctionsQuery {
          /** Case-insensitive substring match on the resolved function name. */
          name?: string;
          /** When true, only functions without a resolved name are returned. */
          unnamed?: boolean;
          /** Only code functions whose body is at least this many bytes are returned. */
          minBodySize?: number;
          /** Zero-based start index into the filtered list. Default 0. */
          offset?: number;
          /** Maximum number of items to return. Default 1000. */
          limit?: number;
        }

        /** One import entry returned by binary.imports(). */
        interface WasmImportEntry {
          /** Import module name. */
          module: string;
          /** Import field name. */
          name: string;
          /** Import kind: "func", "table", "memory", "global", "tag", or "extern". */
          kind: string;
          /** Index in the kind's index space (funcidx for "func" imports). */
          index: number;
        }

        /** One export entry returned by binary.exports(). */
        interface WasmExportEntry {
          /** Export name. */
          name: string;
          /** Export kind: "func", "table", "memory", "global", "tag", or "extern". */
          kind: string;
          /** The index of the exported entity in its respective index space. */
          index: number;
        }
    """.trimIndent() + "\n"

    /**
     * Declarations for the test-suite profile (the hexana suite runner): the
     * registration globals `describe`/`test`/`beforeEach`/`afterEach`,
     * `hexana.artifact`, and a summary of the WinterTC shim surface. These
     * globals exist only when a script runs as a suite, which the script can
     * detect with `hexana.has("suite")`.
     */
    val suiteDeclarations: String = """
        // ---- Test-suite profile ------------------------------------------------
        // The globals below exist only when the script runs as a test suite
        // (hexana.has("suite") === true). The suite profile additionally provides
        // TextEncoder/TextDecoder, URL/URLSearchParams, atob/btoa, structuredClone,
        // queueMicrotask, setTimeout/clearTimeout, and a seeded deterministic
        // crypto: getRandomValues, randomUUID, and
        // subtle.digest("SHA-256" | "SHA-384" | "SHA-512").

        /** Options accepted by test(name, options, fn). */
        interface TestOptions {
          /** Per-test timeout in milliseconds; overrides the runner default. */
          timeout?: number;
        }

        /** Options accepted by hexana.artifact(role, options). */
        interface ArtifactOptions {
          /** Accepted format ids, e.g. ["wasm"]. The mapped file must match one. */
          formats?: string[];
          /** Single accepted format id; shorthand for a one-entry formats list. */
          type?: string;
          /** When true, an unmapped role resolves to null instead of failing the run. */
          optional?: boolean;
        }

        /**
         * Declares a group of tests. The callback runs immediately during the
         * collection pass; nest describe/test/beforeEach/afterEach inside it.
         */
        declare function describe(name: string, fn: () => void): void;

        /** Registers a test. The body may be async; it runs after the module loaded. */
        declare function test(name: string, fn: () => void | Promise<void>): void;
        declare function test(name: string, options: TestOptions, fn: () => void | Promise<void>): void;

        /** Registers a test that is always skipped; the body is never executed. */
        declare namespace test {
          function skip(name: string, fn: () => void | Promise<void>): void;
          function skip(name: string, options: TestOptions, fn: () => void | Promise<void>): void;
        }

        /** Runs before each test in the enclosing describe. Outer hooks run first. */
        declare function beforeEach(fn: () => void | Promise<void>): void;

        /**
         * Runs after each test in the enclosing describe, even when the test
         * failed. Inner hooks run first.
         */
        declare function afterEach(fn: () => void | Promise<void>): void;

        /** Suite-profile extras available on the hexana global. */
        interface HexanaSuiteApi {
          /**
           * Binds the file mapped for a role (--artifact role=path) as a binary
           * handle with the same shape as the `binary` global. A required role
           * with no mapping fails the run before any test executes; with
           * { optional: true } an unmapped role resolves to null instead.
           */
          artifact(role: string, options?: ArtifactOptions): HexanaBinary | null;
        }

        /**
         * Extends the hexana global with the suite-profile artifact() method via
         * interface merging. TypeScript merges this declaration with the HexanaApi
         * interface from the base declarations, so hexana.artifact(...) type-checks
         * in suite scripts without a separate cast.
         */
        interface HexanaApi {
          artifact(role: string, options?: ArtifactOptions): HexanaBinary | null;
        }
    """.trimIndent() + "\n"

    /** Renders the curated `hexana-scripting.d.ts`. */
    fun dts(): String = buildString {
        appendLine("// Hexana scripting API: the globals available in a custom script tab.")
        appendLine("//")
        appendLine("// This file is generated from ScriptApiReference.kt. Everything here behaves")
        appendLine("// identically in IntelliJ IDEA, in VS Code, and in the `run-script` CLI.")
        appendLine("// `open(path)` additionally needs local file access, which every editor has and")
        appendLine("// the browser web app does not; in VS Code it must be called at the top level.")
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

        append(v2Declarations)
        appendLine()
        append(suiteDeclarations)
    }

    private fun StringBuilder.appendDoc(entry: ScriptApiEntry, indent: String = "") {
        appendLine("$indent/**")
        appendWrapped(entry.summary, indent)
        entry.deprecated?.let {
            appendLine("$indent *")
            appendLine("$indent * @deprecated $it")
        }
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
