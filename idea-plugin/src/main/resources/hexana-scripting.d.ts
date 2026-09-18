// Hexana scripting API — the globals available in a custom script tab.
//
// This file is generated from ScriptApiReference.kt. Everything here behaves
// identically in IntelliJ IDEA, in VS Code, and in the `run-script` CLI.
// `open(path)` additionally needs local file access, which every editor has and
// the browser web app does not; in VS Code it must be called at the top level.
// For the format-specific shape of `binary.raw`, see hexana-scripting-raw.d.ts.
//
// Output layout: a script tab renders text and tables in the order the script
// produced them. `print(...)` writes text; `table(...)` starts a table and each
// following `row(...)` adds a row to it. Printing text again ends the table, so
// a script can freely alternate between prose and tables.

/**
 * Appends one line to the tab output, joining the arguments with a space.
 *
 * @deprecated Use console.log, console.error, or console.debug for diagnostics. Use table/row or hexana.defineSlice for tabular data.
 *
 * @example print("format:", binary.format);
 */
declare function print(...values: any[]): void;

/**
 * Starts a table and declares its column headers. Text printed before or
 * after it stays text, so a script can narrate between tables.
 *
 * @example table("Section", "Offset", "Size");
 */
declare function table(...columns: any[]): void;

/**
 * Appends one row to the current table. Cells are escaped, and rows
 * shorter than the header are padded. Requires a preceding table() call.
 *
 * @example row(section.name, section.offset, section.size);
 */
declare function row(...cells: any[]): void;

/**
 * The binary currently open in the editor.
 *
 * @example print(binary.formatName, binary.size);
 */
declare const binary: HexanaBinary;

/**
 * Opens another binary from a local file path and returns the same shape
 * as `binary`, so a script can compare two files. The format is detected
 * from the content. Needs a host with local file access — every editor has
 * it; the web app does not and reports that. Where allowed roots are
 * configured, the path must be inside one. In a VS Code script tab, call
 * it at the top level of the script: the script pauses there while the
 * file is read, which it cannot do inside a function or a callback.
 *
 * @example const before = open("/tmp/before.wasm"); print(binary.size - before.size);
 */
declare function open(path: string): HexanaBinary;

/**
 * The binary open in the editor. Every member below works for every
 * supported format; format-specific data is reachable through `raw`.
 */
interface HexanaBinary {
  /**
   * Stable format id: "wasm", "wasm-component", "elf", "macho",
   * "macho-universal", "pe", "coff", "archive", "zip", "tar", "class",
   * "dex", "arsc", "pcap", "pcapng", "parquet", "arrow", "protobuf" or
   * "binary". Safe to branch on.
   *
   * @example if (binary.format === "elf") print(binary.raw.header.machine);
   */
  readonly format: string;

  /**
   * Human-readable format name as shown in the editor, e.g. "Core Wasm" or
   * "ELF64".
   */
  readonly formatName: string;

  /**
   * File name without its directory, when the host knows it.
   */
  readonly name: string | null;

  /**
   * Full path of the file, or null when there is no file behind the binary —
   * a ZIP entry, a module opened from a table, anything in a browser.
   *
   * @example if (binary.path) print(binary.path);
   */
  readonly path: string | null;

  /**
   * Directory containing the file, or null when `path` is null. It has no
   * trailing separator, except for a root such as `/` or `C:\`, which keeps
   * one because dropping it would name a different place. Join with "/",
   * which works on every platform Hexana runs on, to reach a file next to
   * this one.
   *
   * @example const map = open(binary.dir + "/zephyr.map");
   */
  readonly dir: string | null;

  /**
   * File size in bytes.
   */
  readonly size: number;

  /**
   * Raw byte access.
   */
  readonly bytes: HexanaBytes;

  /**
   * Top-level regions of the binary — Wasm sections, ELF sections, Mach-O
   * segments, and so on — in file order.
   *
   * @example for (const r of binary.structure) print(r.name, r.size);
   */
  readonly structure: HexanaStructureRange[];

  /**
   * Strings extracted from the binary.
   */
  readonly strings: HexanaString[];

  /**
   * Hierarchical size breakdown, or null for formats without size profiling.
   */
  readonly sizeAnalysis: HexanaSizeNode | null;

  /**
   * Names of every table Hexana can show for this binary. Whatever tab you
   * can see in the editor, you can read here.
   *
   * @example print(binary.tabs.join(", "));
   */
  readonly tabs: string[];

  /**
   * Reads one table by name, using the same column names the editor shows. A
   * name from `tabs` that has no content for this binary reads as a table
   * with no rows; only a name that is not in `tabs` at all is an error.
   *
   * @example const exports = binary.tab("Exports");
   */
  tab(name: string): HexanaTab;

  /**
   * Escape hatch: the format-specific provider, unfiltered. Its shape
   * depends on the format — see hexana-scripting-raw.d.ts. Members not
   * listed above are also resolved against it directly, so older scripts
   * keep working.
   *
   * @example if (binary.format === "elf") print(binary.raw.programHeaders.length);
   */
  readonly raw: any;

  /**
   * Paginated, filterable list of functions (Wasm only; other formats return
   * an empty page). Options: name (substring, case-insensitive), unnamed
   * (boolean, only unresolved names), minBodySize (number, code functions
   * only), offset (number), limit (number, default 1000). Returns { items,
   * total, nextOffset? }.
   *
   * @example const page = binary.functions({ name: "malloc", limit: 50 });
   */
  functions(options?: FunctionsQuery): FunctionsPage;

  /**
   * All import entries for Wasm binaries; other formats return an empty
   * array. Each entry: { module, name, kind, index }.
   *
   * @example binary.imports().filter(i => i.kind === "func").forEach(i => print(i.module, i.name));
   */
  imports(): WasmImportEntry[];

  /**
   * All export entries for Wasm binaries; other formats return an empty
   * array. Each entry: { name, kind, index }.
   *
   * @example binary.exports().filter(e => e.kind === "func").forEach(e => print(e.name, e.index));
   */
  exports(): WasmExportEntry[];
}

/**
 * Raw byte access. Offsets are absolute file offsets. Reads outside the
 * binary throw rather than returning a placeholder.
 */
interface HexanaBytes {
  /**
   * Number of bytes in the binary.
   */
  readonly size: number;

  /**
   * Largest valid offset, i.e. `size - 1`.
   */
  readonly lastIndex: number;

  /**
   * Bounds of the valid offset range.
   */
  readonly indices: HexanaIndices;

  /**
   * Reads one byte (0-255). With an argument reads at that offset; without
   * one reads at the internal cursor and advances it. Prefer at().
   */
  get(index?: number): number;

  /**
   * Reads one byte (0-255) at an absolute offset.
   *
   * @example print(binary.bytes.at(0));
   */
  at(index: number): number;

  /**
   * Reads a range of bytes as numbers. At most 1 MiB per call.
   */
  slice(offset: number, length: number): number[];

  /**
   * Space-separated lowercase hex dump of a range. At most 1 MiB per call.
   *
   * @example print(binary.bytes.hex(0, 8));
   */
  hex(offset: number, length: number): string;

  /**
   * Range as text, with non-printable bytes shown as '.'. At most 1 MiB per
   * call.
   */
  ascii(offset: number, length: number): string;

  /**
   * Reads an unsigned 8-bit value.
   */
  u8(offset: number): number;

  /**
   * Reads an unsigned 16-bit value; little-endian by default.
   */
  u16(offset: number, littleEndian?: boolean): number;

  /**
   * Reads an unsigned 32-bit value; little-endian by default.
   */
  u32(offset: number, littleEndian?: boolean): number;

  /**
   * Offset of the first occurrence of a byte pattern, or -1.
   *
   * @example print(binary.bytes.indexOf("\0asm"));
   */
  indexOf(pattern: string | number[], from?: number): number;
}

/**
 * Bounds of the valid byte-offset range.
 */
interface HexanaIndices {
  /**
   * First valid offset, always 0.
   */
  readonly first: number;

  /**
   * Last valid offset.
   */
  readonly last: number;

  /**
   * Alias of first.
   */
  readonly start: number;

  /**
   * Alias of last.
   */
  readonly endInclusive: number;

  /**
   * One past the last valid offset.
   */
  readonly endExclusive: number;

  /**
   * Always 1.
   */
  readonly step: number;
}

/**
 * A top-level region of the binary.
 */
interface HexanaStructureRange {
  /**
   * Region label, e.g. a section or segment name.
   */
  readonly name: string;

  /**
   * Byte offset of the region.
   */
  readonly offset: number;

  /**
   * Region size in bytes.
   */
  readonly size: number;

  /**
   * Name of the editor tab that shows this region, when there is one.
   */
  readonly tab: string | null;
}

/**
 * A string extracted from the binary.
 */
interface HexanaString {
  /**
   * The string itself.
   */
  readonly value: string;

  /**
   * Byte offset, or -1 when unknown.
   */
  readonly offset: number;

  /**
   * Encoded size in bytes, or -1 when unknown.
   */
  readonly size: number;

  /**
   * Position within its source table, or -1.
   */
  readonly index: number;

  /**
   * Origin of the string, e.g. a section name.
   */
  readonly tag: string | null;
}

/**
 * A node of the size breakdown tree.
 */
interface HexanaSizeNode {
  /**
   * Node label.
   */
  readonly name: string;

  /**
   * Total size of this node in bytes.
   */
  readonly size: number;

  /**
   * Byte offset of this node.
   */
  readonly offset: number;

  /**
   * Child nodes; empty for a leaf.
   */
  readonly children: HexanaSizeNode[];

  /**
   * This node's size as a percentage of the given total.
   */
  percentage(parentSize: number): number;
}

/**
 * One table from the editor, resolved by binary.tab(name). Rows are read
 * on demand, so a table with a million rows costs nothing until you touch
 * it.
 */
interface HexanaTab {
  /**
   * Tab name.
   */
  readonly name: string;

  /**
   * Column headers, in order.
   */
  readonly columns: string[];

  /**
   * Number of rows.
   */
  readonly rowCount: number;

  /**
   * Index of a column by header, or -1.
   */
  columnIndex(name: string): number;

  /**
   * Cell text, exactly as the editor displays it.
   *
   * @example print(t.cell(0, t.columnIndex("Name")));
   */
  cell(row: number, column: number): string;

  /**
   * Underlying cell value when it is a primitive — useful for sorting and
   * arithmetic — otherwise the displayed text.
   */
  value(row: number, column: number): string | number | boolean | null;

  /**
   * One row as an array of cell texts.
   */
  row(row: number): string[];

  /**
   * All rows as arrays of cell texts.
   */
  rows(): string[][];
}

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

/** The hexana host API available in every script tab. */
interface HexanaApi {
  /**
   * Registers a typed, paginated table tab. The name must start with "user:".
   * Registering the same name twice in one script is an error.
   */
  defineSlice(spec: SliceSpec): void;
  /** API version. Currently 2. */
  readonly api: number;
  /**
   * Returns true when the named feature is available.
   * Recognised values: "slices".
   */
  has(feature: string): boolean;
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
