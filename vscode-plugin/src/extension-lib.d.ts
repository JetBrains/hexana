/**
 * Type declarations for the Kotlin/JS extension library.
 *
 * These types mirror the `@JsExport` API surface defined in
 * `extension-lib/src/jsMain/kotlin/org/jetbrains/hexana/vscode/lib/`.
 *
 * The library is loaded at runtime via `require()` because it is a
 * webpack-bundled Kotlin/JS artifact, not a normal npm package.
 * This file provides compile-time type safety for the TypeScript host.
 */

/** A single resolved dependency: maps an import name to a file path. */
export interface ResolvedDep {
    readonly importName: string;
    readonly filePath: string;
}

/** Result of a {@link resolveAllDependencies} call. */
export interface DependencyResolution {
    readonly resolved: ResolvedDep[];
}

/** A directory entry returned by {@link FileSystemAccess.listDirectory}. */
export interface FileEntry {
    readonly name: string;
    readonly isDirectory: boolean;
    readonly isFile: boolean;
}

/**
 * Callback interface that TypeScript implements to provide filesystem
 * access to the dependency resolver running inside Kotlin/JS.
 */
export interface FileSystemAccess {
    listDirectory(dir: string): FileEntry[];
    readFile(path: string): Int8Array | null;
    fileExists(path: string): boolean;
    parentDir(path: string): string;
    joinPath(...parts: string[]): string;
    pathRoot(path: string): string;
}

// ── WasmAnalysis ────────────────────────────────────────────────────

/** Returns `true` when the given bytes represent a Wasm component binary. */
export function isComponentBinary(bytes: Int8Array): boolean;

/** Returns `true` when the name is a well-known WASI runtime import. */
export function isWasiRuntimeImport(name: string): boolean;

/** Strips the `@version` suffix from a WIT name. */
export function stripVersion(name: string): string;

/** Parses component export names from a Wasm component binary. */
export function parseComponentExportNames(bytes: Int8Array): string[];

/** Parses component import names from a Wasm component binary. */
export function parseComponentImportNames(bytes: Int8Array): string[];

/** A generated import stub module. */
export interface JsImportModuleStub {
    readonly moduleName: string;
    readonly wasmBytes: Int8Array;
}

/** Generates stub Wasm modules for all imports of the given core Wasm binary. */
export function generateImportStubs(wasmBytes: Int8Array): JsImportModuleStub[];

// ── BrowserRunner ───────────────────────────────────────────────────

/**
 * Generates the HTML page for the browser-based Wasm runner.
 * Backed by the shared `plugins-shared` implementation so that both
 * the IntelliJ plugin and the VS Code extension produce identical output.
 */
export function generateBrowserHtml(wasmFileName: string, exportName: string, programArgs: string): string;

// ── SourceLocationResolver ──────────────────────────────────────────

/** A resolved source location returned by {@link SourceLocationResolver.wasmOffsetToSource}. */
export interface ResolvedSourceLocation {
    readonly file: string;
    /** 1-based line number from DWARF. */
    readonly line: number;
}

/** A Wasm local-index to source-name mapping for CDP variable rendering. */
export interface WasmLocalName {
    readonly index: number;
    readonly name: string;
}

/**
 * One frame in a DWARF inlined call chain, returned by
 * {@link SourceLocationResolver.inlinedFramesAt}.
 */
export interface InlinedFrame {
    /** Resolved function name, or `null` if unavailable. */
    readonly name: string | null;
    /** Source file path at the call site, or `null`. */
    readonly callFile: string | null;
    /** 1-based source line at the call site, or `null`. */
    readonly callLine: number | null;
    /** Source column at the call site, or `null`. */
    readonly callColumn: number | null;
    /** Nesting depth (1 = directly inlined, 2 = inlined inside inlined, …). */
    readonly depth: number;
}

/**
 * A DWARF-described variable whose value lives in Wasm linear memory.
 * Returned by {@link SourceLocationResolver.memoryVariablesAt}.
 *
 * Usage:
 * - If `isFbreg` is true: read `byteSize` bytes from `memories[0]` at address
 *   `(value of wasm local frameBaseLocalIndex) + fbregOffset`.
 *   If `frameBaseIsGlobal` is true, `frameBaseLocalIndex` encodes `-(globalIndex+1)`;
 *   resolve the global instead of a local.
 * - If `isFbreg` is false: read `byteSize` bytes from `memories[0]` at `absoluteAddress`.
 */
export interface MemoryVariable {
    /** DWARF source variable name. */
    readonly name: string;
    /** One-line type description for display (e.g. `"i32"`, `"Point"`, `"[4]i32"`). */
    readonly typeSummary: string;
    /** Signed byte offset from the frame-base pointer (frame-relative vars only). */
    readonly fbregOffset: number;
    /**
     * Wasm local index whose value is the shadow stack pointer (frame-relative vars only).
     * Negative values encode `-(globalIndex+1)` meaning use a wasm global instead.
     */
    readonly frameBaseLocalIndex: number;
    /** Fixed linear-memory address for `DW_OP_addr` (absolute-address vars only). */
    readonly absoluteAddress: number;
    /** Number of bytes to read from linear memory (capped at 4096). */
    readonly byteSize: number;
    /** True when this is a frame-relative variable (use `frameBaseLocalIndex + fbregOffset`). */
    readonly isFbreg: boolean;
    /** True when `frameBaseLocalIndex` is a wasm global index (negated), not a local. */
    readonly frameBaseIsGlobal: boolean;
}

/**
 * DWARF-based source ↔ Wasm byte offset resolver.
 *
 * Created from raw Wasm binary bytes; provides mappings between
 * source file positions and Wasm module-absolute byte offsets
 * used by V8 CDP.
 */
export interface SourceLocationResolver {
    /**
     * Returns all Wasm module-absolute byte offsets that map to the
     * given source file and line number.
     *
     * @param fileName source file name (full path or just the file name)
     * @param line 0-based line number
     */
    sourceToAllWasmOffsets(fileName: string, line: number): Int32Array;

    /**
     * Resolves a Wasm module-absolute byte offset back to a source location.
     *
     * @param offset the byte offset (CDP columnNumber for wasm scripts)
     */
    wasmOffsetToSource(offset: number): ResolvedSourceLocation | null;

    /** Returns all known source file names that have DWARF line mappings. */
    knownSourceFiles(): string[];

    /** Returns whether the given CDP function name has a matching DWARF subprogram entry. */
    hasDwarfSubprogram(functionName: string | null): boolean;

    /** Returns Wasm local-index to DWARF source-name mappings for the given CDP function name. */
    wasmLocalNames(functionName: string | null): WasmLocalName[];

    /**
     * Returns the inlined call chain covering `offset` (module-absolute byte offset,
     * as reported by CDP `columnNumber` for wasm:// scripts), innermost first.
     * Empty when no inlined subroutines cover the offset or DWARF info is absent.
     *
     * @param offset the CDP columnNumber for the wasm:// frame
     */
    inlinedFramesAt(offset: number): InlinedFrame[];

    /**
     * Returns DWARF-described variables for the subprogram covering `offset` whose values
     * live in Wasm linear memory. Frame-relative (`DW_OP_fbreg`) and absolute-address
     * (`DW_OP_addr`) variables are returned; wasm-local-backed variables already visible
     * as V8 scope entries are omitted.
     *
     * @param offset the CDP columnNumber for the wasm:// frame (module-absolute byte offset)
     */
    memoryVariablesAt(offset: number): MemoryVariable[];
}

export namespace SourceLocationResolver {
    /**
     * Creates a resolver from raw Wasm binary bytes.
     * Returns `null` if the binary has no DWARF line info or parsing fails.
     */
    function create(wasmBytes: Int8Array): SourceLocationResolver | null;
}

// ── WasmDisassembly ─────────────────────────────────────────────────

/** One disassembly row: module-absolute byte offset (-1 when unknown) and WAT-style text. */
export interface DisasmRow {
    readonly offset: number;
    readonly instruction: string;
}

/** A function's code-section entry: code-section-local index and module-absolute byte range [start, end). */
export interface DisasmFunctionRange {
    readonly index: number;
    readonly start: number;
    readonly end: number;
}

/**
 * Lazy per-function wasm disassembly backing the DAP `disassemble` request.
 * Function bodies are parsed on first access and cached by code-section-local index.
 */
export interface WasmDisassembly {
    /** Number of functions in the code section. */
    functionCount(): number;
    /** The entry range covering the offset, or null when outside the code section. */
    functionRangeForOffset(moduleAbsoluteOffset: number): DisasmFunctionRange | null;
    /** The entry range of the function at the code-section-local index, or null when out of bounds. */
    functionRange(localIndex: number): DisasmFunctionRange | null;
    /** Disassembly rows of the function (cached; empty on parse failure). */
    rowsForFunction(localIndex: number): DisasmRow[];
}

/** Indexes wasm bytes for disassembly; null when the bytes are not a parseable wasm module. */
export function createWasmDisassembly(wasmBytes: Int8Array): WasmDisassembly | null;

// ── WasmStackShape ──────────────────────────────────────────────────

/** A symbolic operand-stack slot; localRef/globalRef are -1 when absent. */
export interface SymbolicStackSlot {
    readonly type: string;
    readonly provenance: string;
    readonly localRef: number;
    readonly globalRef: number;
}

/** Result of stackAt: slots bottom-to-top, or null with unavailableReason set. */
export interface StackShapeResult {
    readonly slots: SymbolicStackSlot[] | null;
    readonly unavailableReason: string | null;
}

/** Statically reconstructs the wasm operand stack (types + provenance) at an offset. */
export interface WasmStackShape {
    /** Stack state BEFORE the instruction at the module-absolute offset executes. */
    stackAt(offset: number): StackShapeResult;
}

/** Indexes wasm bytes for stack-shape analysis; null when not a parseable core module. */
export function createWasmStackShape(wasmBytes: Int8Array): WasmStackShape | null;

// ── WasmModuleIndex ─────────────────────────────────────────────────

/**
 * The core modules a wasm debug session can stop inside, each with its own
 * disassembly and operand-stack analysis.
 *
 * A plain core module is a single module with id 0. A component-model binary is a
 * container: the runtime compiles each embedded core module separately and the stub
 * addresses a stop as `(moduleId, offset within that module)`, counting from the
 * embedded module's own magic — NOT from the start of the component file. Resolving
 * a component's offset against the whole file finds nothing.
 */
export interface WasmModuleIndex {
    /** Number of core modules the binary can stop in — 1 unless it is a component. */
    readonly moduleCount: number;

    /** Whether the executed binary is a component, so offsets are per embedded core module. */
    readonly isComponent: boolean;

    /**
     * The disassembly of the core module owning the offset, or null when no indexed
     * module has a function body there. Pass a negative `moduleId` when the id is
     * unknown, to have the module deduced from the offset. Offsets passed to the
     * returned object are in that module's own space — the one the stub reports.
     */
    disassemblyFor(moduleId: number, offset: number): WasmDisassembly | null;

    /**
     * The operand-stack analysis of the core module owning the offset, or null when
     * no module has a body there or that module's stack cannot be reconstructed.
     * Same `moduleId` convention as `disassemblyFor`.
     */
    stackShapeFor(moduleId: number, offset: number): WasmStackShape | null;
}

/**
 * Indexes wasm bytes per core module — the module itself for a core module, every
 * embedded core module for a component. Null when neither yields a module with code.
 */
export function createWasmModuleIndex(wasmBytes: Int8Array): WasmModuleIndex | null;

/**
 * Translates an LLDB program counter reported over a wasm gdbstub connection into a
 * byte offset inside the core module the stop is in (the low 32 bits; both
 * wasmtime's and WAMR's stubs encode addresses as
 * `0x4000000000000000 | (moduleId << 32) | offset`).
 *
 * The offset counts from that module's own start, so for a component it must be
 * resolved against the embedded module named by `wasmModuleIdForGdbPcString`.
 *
 * Accepts the DAP `instructionPointerReference` form (`"0x4000000000000052"`) and
 * plain decimal. Returns -1 when the value cannot be parsed.
 */
export function wasmOffsetForGdbPcString(pc: string): number;

/**
 * The id of the core module an LLDB wasm gdbstub PC points into (bits 32–47).
 * Always 0 for a plain core module; a component's ids follow its `module` section
 * order. Returns -1 when the value cannot be parsed.
 */
export function wasmModuleIdForGdbPcString(pc: string): number;

/**
 * Decodes a gdbstub stack/local value hex payload (little-endian, 4 bytes for
 * i32/f32, 8 for i64/f64) per the wasm value type from the static analyzer.
 */
export function decodeWasmStackValueHex(hex: string, typeDisplay: string): string | null;

/** Extracts the hex payload from LLDB `process plugin packet send` output. */
export function parseRawPacketOutput(lines: string[]): string | null;

// ── ShellUtils ──────────────────────────────────────────────────────

/**
 * Splits a string into shell-like arguments, respecting double-quoted
 * segments.
 */
export function splitShellArgs(input: string): string[];

/**
 * Escapes a single shell argument for safe use in a terminal command.
 * When `windows` is `true`, uses double-quote escaping suitable for
 * cmd.exe / PowerShell; otherwise uses POSIX single-quote escaping.
 */
export function shellEscape(arg: string, windows?: boolean): string;

// ── Analytics ────────────────────────────────────────────────────────

/**
 * Initializes the PostHog analytics client.
 *
 * @param storagePath - Path for persisting the anonymous distinct ID.
 * @param telemetryEnabled - Whether the user has opted in to telemetry.
 * @param appName - Editor display name (`vscode.env.appName`). Forks such as
 *   Cursor or Windsurf report their own name here.
 * @param appVersion - Editor version string (`vscode.version`).
 */
export function initAnalytics(storagePath: string, telemetryEnabled: boolean, appName: string, appVersion: string, pluginVersion: string): void;

/**
 * Captures a `binary_opened` event for a file opened in the custom editor.
 *
 * `fileName` is used only for its extension (mapped to a bounded bucket; the
 * raw string is never sent). `bytes` are the file contents; the container
 * format is detected from them on the Kotlin side (Wasm component/core,
 * native ELF/Mach-O/PE/COFF, archive/class by extension, else `generic`).
 * When the format is `generic`, the event is enriched with `generic_ext`
 * (allowlisted extension) and `detected_format` (magic-byte sniff). Pass an
 * `Int8Array` view of the bytes (e.g.
 * `new Int8Array(buf.buffer, buf.byteOffset, buf.byteLength)`).
 */
export function captureBinaryOpened(fileName: string, bytes: Int8Array): void;

/** Captures a `wasm_file_parsed` event. */
export function captureWasmFileParsed(
    fileSizeBytes: number,
    durationMs: number,
    success: boolean,
    functionCount: number | null,
): void;

/**
 * Captures a `hex_tab_selected` event. `binaryFormat` is optional; pass
 * the same snake_case format string used for `captureBinaryOpened` so
 * cross-tab funnels can filter by container format.
 */
export function captureHexTabSelected(tabName: string, binaryFormat?: string | null): void;

/** Captures a `wat_view_activated` event. */
export function captureWatViewActivated(): void;

/** Captures a `hex_view_activated` event. */
export function captureHexViewActivated(): void;

/** Captures a `wasm_unparsed_opcodes` event. */
export function captureWasmUnparsedOpcodes(unparsedCount: number, totalFunctions: number): void;

/** Flushes any pending analytics events. */
export function flushAnalytics(): void;

/** Shuts down the analytics client and flushes pending events. */
export function shutdownAnalytics(): void;

// ── DependencyResolver ──────────────────────────────────────────────

/**
 * Walks up from the given path looking for a directory that contains
 * a workspace marker file (`Cargo.toml`, `package.json`, `.git`).
 */
export function findWorkspaceRoot(wasmFilePath: string, fs: FileSystemAccess): string | null;

/**
 * Resolves component dependencies (including transitive) for the
 * component at the given path.
 */
export function resolveAllDependencies(
    importNames: string[],
    scanRoots: string[],
    wasmFilePath: string,
    fs: FileSystemAccess,
): DependencyResolution;

// ── RuntimeResolver ─────────────────────────────────────────────────

/**
 * Resolves the executable command for a given Wasm runtime.
 *
 * @param runtime      runtime identifier: `"wasmtime"`, `"wamr"`, or `"graalwasm"`
 * @param isWindows    `true` on Windows (appends `.exe` to bare names)
 * @param wasmtimePath custom Wasmtime executable path (empty = use default)
 * @param wamrPath     custom WAMR (iwasm) executable path (empty = use default)
 * @param graalVmHome  GraalVM home directory (empty = not configured)
 * @return the resolved executable path, or `null` when the runtime
 *         requires a home directory that was not provided (GraalWasm)
 */
export function resolveRuntimeExecutable(
    runtime: string,
    isWindows: boolean,
    wasmtimePath?: string,
    wamrPath?: string,
    wazeroPath?: string,
    graalVmHome?: string,
    nodePath?: string,
): string | null;

/**
 * Builds a unified runtime command line by dispatching to the
 * correct shared builder.
 */
export function buildRuntimeCommandLine(
    runtime: string,
    wasmFilePath: string,
    wasmBytes?: Int8Array | null,
    exportName?: string,
    invokeArgs?: string,
    programArgs?: string,
    effectiveWasmPath?: string | null,
    stubWriter?: JsStubFileWriter | null,
): CommandLineResult;

// ── ComponentComposition ────────────────────────────────────────────

/**
 * Callback interface that TypeScript implements to execute external
 * CLI tools (e.g. `wasm-tools compose`, `wac plug`).
 */
export interface JsProcessRunner {
    /**
     * Executes a CLI command synchronously.
     * Returns an empty string on success, or the error message on failure.
     */
    exec(command: string, args: string[]): string;
}

/**
 * Callback interface that TypeScript implements to resolve the path
 * to CLI tools and check file existence.
 */
export interface JsToolResolver {
    resolveToolPath(toolName: string): string | null;
    fileExists(path: string): boolean;
}

/** Result of a component composition attempt. */
export interface CompositionResult {
    readonly wasmPath: string;
    readonly composed: boolean;
    readonly error: string | null;
}

/**
 * Attempts to compose component dependencies using `wasm-tools compose`
 * or `wac plug`, with iterative passes for transitive dependencies.
 */
export function composeComponents(
    mainWasmPath: string,
    depNames: string[],
    depPaths: string[],
    tempDir: string,
    processRunner: JsProcessRunner,
    toolResolver: JsToolResolver,
    fs: FileSystemAccess,
): CompositionResult;

// ── RuntimeCommandLine ──────────────────────────────────────────────

/** Result of building a Wasm runtime command line. */
export interface CommandLineResult {
    readonly arguments: string[];
    readonly workingDirectory: string | null;
}

/**
 * Callback interface that TypeScript implements to provide temporary
 * file writing for import stub generation.
 */
export interface JsStubFileWriter {
    createTempDir(prefix: string): string;
    writeFile(dir: string, fileName: string, bytes: Int8Array): string;
}

/**
 * Builds the argument list for a Wasmtime `run` invocation using
 * the shared command-line builder.
 */
export function buildWasmtimeCommandLine(
    wasmFilePath: string,
    wasmBytes?: Int8Array | null,
    programArguments?: string,
    runtimeOptions?: string,
    effectiveWasmPath?: string | null,
    stubWriter?: JsStubFileWriter | null,
): CommandLineResult;

/**
 * Builds the argument list for a WAMR `iwasm` invocation using
 * the shared command-line builder.
 */
export function buildWamrCommandLine(
    wasmFilePath: string,
    wasmBytes?: Int8Array | null,
    programArguments?: string,
    runtimeOptions?: string,
    exportName?: string,
    stubWriter?: JsStubFileWriter | null,
): CommandLineResult;

/**
 * Builds the argument list for an external GraalVM `wasm` launcher
 * using the shared command-line builder.
 */
export function buildGraalWasmCommandLine(
    wasmFilePath: string,
    programArguments?: string,
    exportName?: string,
): CommandLineResult;

// ── Debug command-line builders ─────────────────────────────────────

/** Result of building a Wasmtime debug command line. */
export interface WasmtimeDebugCommandLineResult {
    readonly arguments: string[];
    readonly workingDirectory: string | null;
}

/**
 * Builds the argument list for a Wasmtime debug invocation.
 * Adds `-D debug-info -O opt-level=0` flags for DWARF translation.
 */
export function buildWasmtimeDebugCommandLine(
    wasmFilePath: string,
    wasmBytes?: Int8Array | null,
    programArguments?: string,
    runtimeOptions?: string,
    effectiveWasmPath?: string | null,
): WasmtimeDebugCommandLineResult;

/** Result of building a WAMR debug command line. */
export interface WamrDebugCommandLineResult {
    readonly arguments: string[];
    readonly workingDirectory: string | null;
    readonly debugHost: string;
    readonly debugPort: number;
}

/**
 * Builds the argument list for a WAMR (iwasm) debug invocation.
 * The `-g=host:port` flag starts a GDB remote stub.
 */
export function buildWamrDebugCommandLine(
    wasmFilePath: string,
    wasmBytes?: Int8Array | null,
    programArguments?: string,
    runtimeOptions?: string,
    exportName?: string,
    host?: string,
    port?: number,
    stubWriter?: JsStubFileWriter | null,
): WamrDebugCommandLineResult;

/** Result of building a Wasmtime gdbstub command line. */
export interface WasmtimeGdbCommandLineResult {
    readonly arguments: string[];
    readonly workingDirectory: string | null;
    readonly debugHost: string;
    readonly debugPort: number;
}

/**
 * Builds the argument list for a Wasmtime guest-debugging run using the
 * built-in gdbstub (`wasmtime run -g host:port module.wasm`). No JIT
 * loader / `-D debug-info` — LLDB attaches via `process connect --plugin wasm`.
 */
export function buildWasmtimeGdbCommandLine(
    wasmFilePath: string,
    wasmBytes?: Int8Array | null,
    programArguments?: string,
    runtimeOptions?: string,
    host?: string,
    port?: number,
    effectiveWasmPath?: string | null,
): WasmtimeGdbCommandLineResult;

/**
 * Detects whether iwasm output indicates the `-g` debug option is
 * not supported by the current build.
 */
export function isWamrDebugNotSupported(output: string): boolean;

// ── WasiSdk ─────────────────────────────────────────────────────────
//
// Thin wrappers around the shared WasiSdkReleases Kotlin object so TypeScript
// does not duplicate the logic. Each function is exported top-level from
// extension-lib (matching the WasmAnalysis pattern).

/** Oldest wasi-sdk version that bundles a working LLDB binary. */
export const wasiSdkMinVersionWithLldb: number;

/**
 * Parses a GitHub tag name to a version number.
 * Returns null for rc/prerelease tags, non-matching tags, or versions
 * below `wasiSdkMinVersionWithLldb`.
 */
export function wasiSdkParseTag(tag: string): number | null;

/**
 * Picks the asset name matching `version`/`os`/`arch` from `assetNames`,
 * or returns null if none matches.
 *
 * @param os one of "macos", "linux", "windows"
 * @param arch one of "x86_64", "arm64"
 */
export function wasiSdkSelectAsset(assetNames: string[], version: number, os: string, arch: string): string | null;

/**
 * Extracts the hex digest from a "sha256:<hex>" string.
 * Returns null for null, blank, or non-sha256 input.
 */
export function wasiSdkParseSha256Digest(digest: string | null | undefined): string | null;

/** Directory name for an installed wasi-sdk version (e.g. "wasi-sdk-33.0"). */
export function wasiSdkInstallDirName(version: number): string;

/**
 * Top-level directory name inside a wasi-sdk tarball
 * (e.g. "wasi-sdk-33.0-arm64-macos").
 *
 * @param os one of "macos", "linux", "windows"
 * @param arch one of "x86_64", "arm64"
 */
export function wasiSdkArchiveTopLevelDir(version: number, os: string, arch: string): string;

/**
 * Relative path to the lldb binary inside an installed wasi-sdk directory
 * ("bin/lldb" or "bin/lldb.exe" on Windows).
 *
 * @param os one of "macos", "linux", "windows"
 */
export function wasiSdkLldbRelativePath(os: string): string;
