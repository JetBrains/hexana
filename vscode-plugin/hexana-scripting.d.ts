// Hexana scripting API — the globals available in a custom script tab.
//
// This file is generated from ScriptApiReference.kt. Everything here behaves
// identically in IntelliJ IDEA, in VS Code, and in the `run-script` CLI.
// For the format-specific shape of `binary.raw`, see hexana-scripting-raw.d.ts.

/**
 * Appends one line to the tab output, joining the arguments with a space.
 *
 * @example print("format:", binary.format);
 */
declare function print(...values: any[]): void;

/**
 * Declares the table header. Output that starts with a header row is
 * rendered as a searchable table instead of plain text.
 *
 * @example table("Section", "Offset", "Size");
 */
declare function table(...columns: any[]): void;

/**
 * Appends one table row. Cells are escaped, and rows shorter than the
 * header are padded. Requires a preceding table() call.
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
 * The binary open in the editor. Every member below works for every
 * supported format; format-specific data is reachable through `raw`.
 */
interface HexanaBinary {
  /**
   * Stable format id: "wasm", "wasm-component", "elf", "macho",
   * "macho-universal", "pe", "coff", "archive", "zip", "tar", "class",
   * "dex", "pcap", "pcapng", "parquet", "arrow", "protobuf" or "binary".
   * Safe to branch on.
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
   * File name, when the host knows it.
   */
  readonly name: string | null;

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
   * Reads one table by name, using the same column names the editor shows.
   * Throws with the list of available names when there is no such tab.
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

