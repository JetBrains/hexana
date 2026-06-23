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
}

export namespace SourceLocationResolver {
    /**
     * Creates a resolver from raw Wasm binary bytes.
     * Returns `null` if the binary has no DWARF line info or parsing fails.
     */
    function create(wasmBytes: Int8Array): SourceLocationResolver | null;
}

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
export function initAnalytics(storagePath: string, telemetryEnabled: boolean, appName: string, appVersion: string): void;

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

/**
 * Detects whether iwasm output indicates the `-g` debug option is
 * not supported by the current build.
 */
export function isWamrDebugNotSupported(output: string): boolean;
