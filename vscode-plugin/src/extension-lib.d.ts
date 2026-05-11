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

/** Captures a `wasm_file_opened` event. */
export function captureWasmFileOpened(fileSizeBytes: number): void;

/** Captures a `wasm_file_parsed` event. */
export function captureWasmFileParsed(
    fileSizeBytes: number,
    durationMs: number,
    success: boolean,
    functionCount: number | null,
): void;

/** Captures a `hex_tab_selected` event. */
export function captureHexTabSelected(tabName: string): void;

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
