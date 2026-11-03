import * as path from "path";
import * as zlib from "zlib";
import * as vscode from "vscode";
import { getWebviewContent } from "./webviewContent";
import { extensionLib, getSelectedRuntime, runExport } from "./WasmRunConfigurations";
import { isNativeBinary } from "./NativeBinaryFileTypeDetector";
import { detectAndroidSdk, runApkOnEmulator } from "./apkRunner";
import { DIFF_LABEL_SEPARATOR, pairSidesForLabel } from "./diffTabPairing";
import { WasmFeatureCatalogProvider } from "./wasmFeatureCatalog";
import {
    binaryFileContent,
    binaryFileReadFailure,
    errorMessageText,
    fileReadFailureReason,
    scriptOpenOversizeReason,
    toWebviewBytes,
} from "./scriptBinaryRead";
import {
    HttpReplaySendMessage,
    HttpRequestFileMessage,
    REST_CLIENT_EXTENSION_ID,
    sendHttpReplay,
    writeHttpRequestFile,
} from "./httpReplay";

/**
 * In-memory store for nested module byte arrays.
 *
 * When the webview asks to open a nested module we store the raw bytes here
 * keyed by a synthetic `hexana-module:` URI, then ask VS Code to open that
 * URI with our custom editor.  `openCustomDocument` checks this map first
 * so it can serve the data without touching the file system.
 */
const inMemoryModules = new Map<string, { data: Uint8Array; fileName: string }>();

/** A top-level row of a table tab, for a `select-table-row` message to a webview. */
interface TableRowSelection {
    tabName: string;
    rowIndex: number;
}

/** The `hexana-module:` URI an archive entry opens under; stable, so an open entry is found again. */
function zipEntryModuleUri(childName: string, parentUri: vscode.Uri, entryName: string): vscode.Uri {
    return vscode.Uri.parse(
        `hexana-module:/${childName}?parent=${encodeURIComponent(parentUri.toString())}&entry=${encodeURIComponent(entryName)}`,
    );
}

/**
 * Type-definition sidecar written next to a script so the editor resolves the
 * scripting globals. Shipped with the extension and copied into the script's
 * folder; its content is the curated reference, not a hand-written stub.
 */
const HEXANA_SCRIPT_TYPES_FILE = "hexana-scripting.d.ts";

/**
 * Config sidecar that puts {@link HEXANA_SCRIPT_TYPES_FILE} in scope for a
 * standalone `.js` file, which otherwise has no association with a sibling
 * `.d.ts`. Its content comes from the webview so the JSON is defined once,
 * alongside the rest of the scripting API contract.
 */
const HEXANA_SCRIPT_JSCONFIG_FILE = "jsconfig.json";

/**
 * Project-relative directory Hexana keeps shared, editor-independent state in, and the file
 * inside it that holds the target runtimes for the compatibility table.
 *
 * Mirrors `HEXANA_PROJECT_DIR` / `RUNTIME_TARGETS_FILE` in
 * `plugins-shared/src/commonMain/kotlin/org/jetbrains/hexana/shared/features/RuntimeTargets.kt`,
 * which is the source of truth for the format. The IntelliJ plugin reads and writes the same
 * file, so a selection made in either editor is honoured by the other.
 *
 * Project-relative, so a multi-root workspace has one per folder; see [runtimeTargetsFolder].
 */
const HEXANA_PROJECT_DIR = ".hexana";
const RUNTIME_TARGETS_FILE = "runtimes.json";

/**
 * Generated JSON Schema written next to [RUNTIME_TARGETS_FILE], which references it. It is
 * what makes the settings file hand-editable: with it present, VS Code completes the keys
 * and the runtime names and flags a typo. The webview generates the content, since the
 * runtime names come from the feature catalog it already holds.
 */
const RUNTIME_TARGETS_SCHEMA_FILE = "runtimes.schema.json";

/**
 * File inside [HEXANA_PROJECT_DIR] holding the parser chosen by hand for individual files,
 * and its generated JSON Schema. Mirrors `FILE_ASSOCIATIONS_FILE` /
 * `FILE_ASSOCIATIONS_SCHEMA_FILE` in
 * `plugins-shared/src/commonMain/kotlin/org/jetbrains/hexana/shared/features/FileAssociations.kt`,
 * the source of truth for the format; the webview renders both, so this side only moves
 * text. Governed by the same folder as the runtime targets ([runtimeTargetsFolder]).
 */
const FILE_ASSOCIATIONS_FILE = "file_associations.json";
const FILE_ASSOCIATIONS_SCHEMA_FILE = "file_associations.schema.json";

/**
 * Directory inside [HEXANA_PROJECT_DIR] whose `*.js` files define binary providers and tabs
 * (`hexana.defineProvider` / `hexana.defineTab`). Mirrors `PROVIDER_SCRIPTS_DIR` in
 * `plugins-shared/.../shared/scripting/ScriptProviders.kt`. The webview evaluates them; this
 * side only reads them, and only in a trusted workspace.
 */
const PROVIDER_SCRIPTS_DIR = "providers";

/** One provider script as sent to the webview. */
interface ProviderScriptSource {
    path: string;
    text: string;
}

/**
 * Reads the provider scripts governing [scope], in name order. Nothing in an untrusted
 * workspace: these scripts run on their own whenever a binary opens, unlike a script tab the
 * user adds, so opening a cloned repository must not be enough to run its code.
 */
async function readProviderScripts(scope?: vscode.Uri): Promise<{ scripts: ProviderScriptSource[]; untrusted: boolean }> {
    const folder = runtimeTargetsFolder(scope);
    if (!folder) {
        return { scripts: [], untrusted: false };
    }
    const directory = vscode.Uri.joinPath(folder.uri, HEXANA_PROJECT_DIR, PROVIDER_SCRIPTS_DIR);
    let entries: [string, vscode.FileType][];
    try {
        entries = await vscode.workspace.fs.readDirectory(directory);
    } catch {
        return { scripts: [], untrusted: false };
    }
    const names = entries
        .filter(([name, type]) => type === vscode.FileType.File && name.endsWith(".js"))
        .map(([name]) => name)
        .sort();
    if (names.length === 0) {
        return { scripts: [], untrusted: false };
    }
    if (!vscode.workspace.isTrusted) {
        return { scripts: [], untrusted: true };
    }
    const decoder = new TextDecoder("utf-8");
    const scripts: ProviderScriptSource[] = [];
    for (const name of names) {
        const file = vscode.Uri.joinPath(directory, name);
        try {
            scripts.push({ path: file.fsPath, text: decoder.decode(await vscode.workspace.fs.readFile(file)) });
        } catch {
            // Unreadable right now (mid-save, deleted): skipped; the watcher brings it back.
        }
    }
    return { scripts, untrusted: false };
}

/**
 * The workspace folder whose `.hexana/runtimes.json` governs the binary at [scope].
 *
 * A multi-root workspace holds one settings file per folder, so the folder the *file* lives
 * in decides, not the first one in the list. A nested module (`hexana-module:`) belongs to
 * whatever file it was extracted from, so the chain is walked back to that file first.
 *
 * Anything still outside every folder - a file opened on its own, a module whose parent
 * cannot be resolved - falls back to the first folder, which is also the only folder in the
 * single-root case this used to assume.
 */
function runtimeTargetsFolder(scope?: vscode.Uri): vscode.WorkspaceFolder | undefined {
    const owning = scope ? vscode.workspace.getWorkspaceFolder(originatingFile(scope)) : undefined;
    return owning ?? vscode.workspace.workspaceFolders?.[0];
}

/** Follows a nested module's `parent` query back to the file on disk it came from. */
function originatingFile(uri: vscode.Uri): vscode.Uri {
    let current = uri;
    // Bounded rather than open: a module can be nested twice (a component inside an archive),
    // but a hand-crafted or corrupted query must not spin here.
    for (let depth = 0; depth < 8 && current.scheme === "hexana-module"; depth++) {
        const parent = new URLSearchParams(current.query).get("parent");
        if (!parent) {
            break;
        }
        try {
            current = vscode.Uri.parse(decodeURIComponent(parent));
        } catch {
            break;
        }
    }
    return current;
}

/**
 * Reads `.hexana/file_associations.json` from the workspace folder that governs [scope], or
 * an empty string when there is none - which the webview reads as "detect every file".
 */
async function readFileAssociationsJson(scope?: vscode.Uri): Promise<string> {
    const folder = runtimeTargetsFolder(scope);
    if (!folder) {
        return "";
    }
    const file = vscode.Uri.joinPath(folder.uri, HEXANA_PROJECT_DIR, FILE_ASSOCIATIONS_FILE);
    try {
        return new TextDecoder("utf-8").decode(await vscode.workspace.fs.readFile(file));
    } catch {
        return "";
    }
}

/**
 * The key [uri] is stored under in `.hexana/file_associations.json`: its path relative to
 * the workspace folder that contains it, with `/` separators. Empty when the file cannot
 * carry a choice - not on disk (an archive entry, a nested module), or outside every
 * folder - and the webview then shows a plain format label.
 *
 * Unlike [runtimeTargetsFolder] there is no fallback to the first folder: a path relative
 * to a folder the file is not in would name some other file.
 */
function fileAssociationKey(uri: vscode.Uri): string {
    if (uri.scheme !== "file") {
        return "";
    }
    const folder = vscode.workspace.getWorkspaceFolder(uri);
    if (!folder) {
        return "";
    }
    const relative = path.relative(folder.uri.fsPath, uri.fsPath);
    // A leading `..` *segment* climbs out; a name merely starting with dots (`..cache/x`) is
    // inside the folder.
    if (!relative || relative.split(path.sep)[0] === ".." || path.isAbsolute(relative)) {
        return "";
    }
    return relative.split(path.sep).join("/");
}

/**
 * Reads `.hexana/runtimes.json` from the workspace folder that governs [scope].
 *
 * Returns an empty string when there is no workspace, no file, or the file cannot be read.
 * The webview treats that as "nothing declared" and the compatibility table simply has
 * nothing to check against - an unreadable settings file is not worth an error popup on
 * every file open.
 */
async function readRuntimeTargetsJson(scope?: vscode.Uri): Promise<string> {
    const folder = runtimeTargetsFolder(scope);
    if (!folder) {
        return "";
    }
    const file = vscode.Uri.joinPath(folder.uri, HEXANA_PROJECT_DIR, RUNTIME_TARGETS_FILE);
    try {
        return new TextDecoder("utf-8").decode(await vscode.workspace.fs.readFile(file));
    } catch {
        return "";
    }
}

/**
 * Extensions an archive entry may carry for Hexana to open it in a tab of its own.
 *
 * Only a hint: an entry whose bytes carry a recognizable magic is opened regardless
 * (see `isNativeBinary`). This list exists for the formats magic alone cannot
 * identify — a tar has its `ustar` marker 257 bytes in, a Protobuf wire message has
 * no marker at all — and it deliberately mirrors the `customEditors`
 * `filenamePattern` in `package.json`, so an entry opens exactly when the same file
 * on disk would.
 */
/**
 * Extensions whose files a VS Code diff of two of them is redirected to Hexana's diff for:
 * those the `hexana.wasmEditor` custom editor claims in `package.json`, plus the native ones
 * the compare dialog offers.
 */
const HEXANA_DIFF_EXTENSIONS = new Set([
    "wasm", "elf", "exe", "so", "o", "a", "dll", "dylib", "jar", "zip", "war", "apk", "class", "dex", "arsc",
    "resources", "desc", "protoset", "pcap", "cap", "pcapng", "parquet", "arrow", "arrows", "pb", "tar", "tgz", "gz",
    "obj", "lib", "ko", "sys", "axf", "bin", "out",
]);

/** Native-binary extensions offered by the compare file dialog (besides `.wasm`). */
const COMPARABLE_NATIVE_EXTENSIONS = ["o", "obj", "so", "dylib", "dll", "exe", "elf", "a", "lib", "ko", "sys", "axf", "bin", "out"];

/**
 * Extensions shared with text formats, so only the content decides: `.pdb` is both PDB debug
 * symbols and the Protein Data Bank text format.
 */
const CONTENT_TYPED_EXTENSIONS = new Set(["pdb"]);

const OPENABLE_ENTRY_EXTENSIONS = new Set([
    "wasm", "class", "dex", "arsc", "resources", "jar", "zip", "war", "apk", "bin",
    "elf", "exe", "so", "o", "a", "dll", "dylib",
    "tar", "tgz", "gz", "desc", "protoset", "pcap", "cap", "pcapng",
    "parquet", "arrow", "arrows", "pb",
]);

/**
 * Hard cap on an inflated gzip payload, mirroring `MAX_SBOM_INFLATED_BYTES` in
 * `binaryProvider`'s SBOM extraction.
 *
 * A gzip stream declares nothing about its output size, so a few kilobytes on disk can
 * inflate to gigabytes. `zlib.gunzipSync` allocates that unbounded, in the extension
 * host process, which would take the whole window down. Past the cap zlib throws and
 * the file is shown as the compressed bytes it is.
 */
const MAX_INFLATED_BYTES = 128 * 1024 * 1024;

/**
 * Name the contents of a gzipped `fileName` should be presented under, or `null`
 * when the file is not gzipped and must be passed through untouched.
 *
 * The webview picks a `BinaryProvider` from the bytes plus this name, so the name
 * has to describe the *inflated* content: `module.wasm.gz` carries a `module.wasm`,
 * and both `bundle.tgz` and `bundle.tar.gz` carry a `bundle.tar`, which is what makes
 * the shared factory build a `TarBinaryProvider` and the Entries tab appear.
 *
 * Mirrors the `.gz` / `.tgz` / `.tar.gz` branches of the IntelliJ plugin's
 * `HexanaFileEditor.resolveProvider`.
 */
function gzipInflatedName(fileName: string): string | null {
    const lower = fileName.toLowerCase();
    if (lower.endsWith(".tgz")) {
        return `${fileName.substring(0, fileName.length - 4)}.tar`;
    }
    if (lower.endsWith(".gz")) {
        return fileName.substring(0, fileName.length - 3);
    }
    return null;
}

/**
 * Inflates `data` when `fileName` says it is gzipped, returning the bytes to show and
 * the name to show them under (`null` when nothing was inflated).
 *
 * Shared by the two ways a payload reaches the editor — a file opened from disk, and an
 * entry extracted from a ZIP or TAR — because the second used to skip inflation
 * entirely: entries are stashed in `inMemoryModules` and served from a branch that
 * returned before the `.gz` handling, so a `module.wasm.gz` inside an archive opened as
 * a hex dump of the gzip stream.
 *
 * Inflation failure is not fatal: the original bytes are returned and the file opens as
 * whatever it actually is.
 */
function inflateIfGzipped(fileName: string, data: Uint8Array): { data: Uint8Array; displayName?: string } {
    const inflatedName = gzipInflatedName(fileName);
    if (inflatedName === null) {
        return { data };
    }
    try {
        return {
            data: zlib.gunzipSync(data, { maxOutputLength: MAX_INFLATED_BYTES }),
            displayName: inflatedName,
        };
    } catch (err) {
        console.error("[Hexana] Failed to decompress gzipped file:", err);
        return { data };
    }
}

/**
 * In-memory store for pending diff payloads keyed by a synthetic
 * `hexana-module:` URI.  When the user invokes "Hexana: Compare Files" we
 * store both byte arrays here and open a new custom editor tab whose URI
 * matches the key.  `resolveCustomEditor` checks this map and, when a
 * diff entry is present, sends a `load-diff` message instead of the
 * normal `load-wasm`.
 */
const pendingDiffs = new Map<string, {
    leftData: Uint8Array; leftName: string;
    rightData: Uint8Array; rightName: string;
    mode: DiffMode;
}>();

/** How long a resolved side or an unmatched diff tab waits for its counterpart. */
const DIFF_MATCH_WINDOW_MS = 3000;

/** Sides the Hexana editor resolved just now — candidates for a custom-editor diff tab. */
const recentSides: { uri: vscode.Uri; at: number }[] = [];

/** Unknown-input `a ↔ b` tabs waiting for their sides to resolve. */
const pendingDiffTabs: { tab: vscode.Tab; at: number }[] = [];

function prune(entries: { at: number }[], now: number): void {
    for (let i = entries.length - 1; i >= 0; i--) {
        if (now - entries[i].at > DIFF_MATCH_WINDOW_MS) entries.splice(i, 1);
    }
}

/**
 * Which view a diff opens on: the hex diff (sections, table tabs; any pair) or the Hexana
 * structural diff. The webview offers a switch between them either way.
 */
type DiffMode = "hex" | "hexana";

/**
 * In-memory store for WAT text keyed by a synthetic `hexana-wat:` URI.
 *
 * When the webview produces WAT output for a wasm binary, it sends the text
 * to the extension host which stores it here and opens a native VS Code
 * editor tab backed by a `TextDocumentContentProvider`.
 */
const watDocuments = new Map<string, string>();

/**
 * Content provider for virtual `hexana-wat:` documents.
 *
 * VS Code's Monaco editor uses this to display WAT text with full editor
 * features (syntax highlighting, search, folding, etc.).
 */
class WatContentProvider implements vscode.TextDocumentContentProvider {
    private _onDidChange = new vscode.EventEmitter<vscode.Uri>();
    readonly onDidChange = this._onDidChange.event;

    provideTextDocumentContent(uri: vscode.Uri): string {
        return watDocuments.get(uri.toString()) ?? "";
    }

    update(uri: vscode.Uri): void {
        this._onDidChange.fire(uri);
    }
}

const watContentProvider = new WatContentProvider();

/**
 * Minimal read-only file-system provider for the `hexana-module` URI scheme.
 *
 * VS Code requires a registered `FileSystemProvider` before `vscode.openWith`
 * can open URIs with a custom scheme.  This provider serves bytes that were
 * previously stored in `inMemoryModules`.
 */
class HexanaModuleFS implements vscode.FileSystemProvider {
    private _onDidChangeFile = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
    readonly onDidChangeFile = this._onDidChangeFile.event;

    watch(): vscode.Disposable {
        return new vscode.Disposable(() => {});
    }

    stat(uri: vscode.Uri): vscode.FileStat {
        const entry = inMemoryModules.get(uri.toString());
        if (entry) {
            return {
                type: vscode.FileType.File,
                ctime: 0,
                mtime: 0,
                size: entry.data.byteLength,
            };
        }
        // Diff or nested-module URIs whose in-memory data was lost after
        // a VS Code restart — return an empty placeholder so the editor
        // can open and show a friendly "session expired" message.
        if (uri.scheme === "hexana-module") {
            return {
                type: vscode.FileType.File,
                ctime: 0,
                mtime: 0,
                size: 0,
            };
        }
        throw vscode.FileSystemError.FileNotFound(uri);
    }

    readFile(uri: vscode.Uri): Uint8Array {
        const entry = inMemoryModules.get(uri.toString());
        if (entry) {
            return entry.data;
        }
        // Return empty bytes for expired in-memory URIs so the editor
        // can open and display a recovery message.
        if (uri.scheme === "hexana-module") {
            return new Uint8Array(0);
        }
        throw vscode.FileSystemError.FileNotFound(uri);
    }

    readDirectory(): [string, vscode.FileType][] {
        return [];
    }

    createDirectory(): void {}
    writeFile(): void {
        throw vscode.FileSystemError.NoPermissions("read-only");
    }
    delete(): void {
        throw vscode.FileSystemError.NoPermissions("read-only");
    }
    rename(): void {
        throw vscode.FileSystemError.NoPermissions("read-only");
    }
}

/**
 * Read-only custom editor provider for `.wasm` files.
 *
 * The provider reads the binary document, creates a webview that hosts the
 * Compose-for-Web viewer, and posts the raw bytes into the webview so that the
 * Kotlin/JS code can parse and render them.
 */
export class WasmEditorProvider implements vscode.CustomReadonlyEditorProvider<WasmDocument> {

    private static readonly viewType = "hexana.wasmEditor";

    /**
     * A second view type contributed against `*` at `priority: "option"`, so Hexana is
     * offered under "Reopen Editor With…" for a file whose name none of the default
     * patterns claim — an Arrow IPC stream written as `.stream`, a firmware image as
     * `.dat`, a payload with no extension at all. Magic-byte interception
     * (`isNativeBinary`) covers the formats that announce themselves; this covers the
     * rest, where only the user knows what the bytes are.
     *
     * `"option"` and not `"default"` for two reasons: VS Code must not start opening
     * every text file in the workspace as hex, and two *default* editors matching one
     * file suppress auto-opening altogether and force the picker instead — which would
     * mean a `.wasm` no longer opens in Hexana on its own.
     *
     * Internal `openWith` calls and the `workbench.editorAssociations` entries the
     * detector persists all keep using [viewType], so nothing Hexana already claims
     * starts going through the picker.
     */
    private static readonly anyFileViewType = "hexana.binaryEditor";

    private static readonly nativeExtensions = new Set([
        "o", "obj", "so", "dylib", "dll", "exe", "elf", "a", "lib",
        "ko", "sys", "axf", "bin", "out",
    ]);

    /**
     * Returns true when the URI looks like a native binary: either it has a
     * known native extension, or the file name has no extension at all
     * (extensionless files are common for native executables on Unix).
     */
    private static isNativeBinary(uri: vscode.Uri): boolean {
        const base = path.basename(uri.fsPath);
        if (!base.includes(".")) return true; // extensionless → treat as native
        const ext = base.split(".").pop()?.toLowerCase() ?? "";
        return WasmEditorProvider.nativeExtensions.has(ext);
    }

    /**
     * Registers `hexana.configureWasmRuntimes`, which opens the target-runtimes dialog in
     * the active Hexana editor.
     *
     * The dialog lives inside the editor webview rather than in VS Code settings because the
     * selection is written to the project's `.hexana/runtimes.json`, so the command needs a
     * webview to talk to.
     */
    public static registerConfigureRuntimesCommand(provider: WasmEditorProvider): vscode.Disposable {
        return vscode.commands.registerCommand("hexana.configureWasmRuntimes", () => {
            if (!provider.openWebviews.size) {
                void vscode.window.showInformationMessage(
                    "Open a Wasm or binary file in Hexana first: target runtimes are configured from its editor.",
                );
                return;
            }
            provider.broadcast({ type: "open-runtime-targets-dialog" });
        });
    }

    /**
     * Registers `hexana.configureFileAssociations`, which opens the file-parser
     * associations dialog in the active Hexana editor - inside the webview for the reason
     * the runtimes dialog is: the list is `.hexana/file_associations.json`, and the webview
     * owns its format.
     */
    public static registerConfigureFileAssociationsCommand(provider: WasmEditorProvider): vscode.Disposable {
        return vscode.commands.registerCommand("hexana.configureFileAssociations", () => {
            if (!provider.openWebviews.size) {
                void vscode.window.showInformationMessage(
                    "Open a binary file in Hexana first: file parser associations are configured from its editor.",
                );
                return;
            }
            provider.broadcast({ type: "open-file-associations-dialog" });
        });
    }

    /**
     * Registers `hexana.compareFiles`: pick two binaries of any supported format and open
     * a diff view in a new custom editor tab. The webview picks the comparison from the
     * bytes (core WASM, Component Model, native), and reports a pair that is not the same
     * kind.
     *
     * The former `hexana.compareWasm` / `hexana.compareBinary` ids are registered as
     * aliases so keybindings bound to them keep working. They are not contributed in
     * `package.json`, so the Command Palette and menus offer only `hexana.compareFiles`.
     */
    public static registerCompareFilesCommand(context: vscode.ExtensionContext): vscode.Disposable {
        const handler = async (contextUri?: vscode.Uri, selectedUris?: vscode.Uri[]) => {
            const isComparable = (uri: vscode.Uri) =>
                uri.fsPath.toLowerCase().endsWith(".wasm") || WasmEditorProvider.isNativeBinary(uri);
            const filters = { "Binaries": ["wasm", ...COMPARABLE_NATIVE_EXTENSIONS, "*"] };

            let left: vscode.Uri | undefined;
            let right: vscode.Uri | undefined;
            // When exactly two comparable files are selected in the explorer, use them directly.
            if (selectedUris && selectedUris.length === 2 && selectedUris.every(isComparable)) {
                [left, right] = selectedUris;
            } else {
                // Left file: use the context-menu URI if available, otherwise prompt.
                left = contextUri && !contextUri.fsPath.endsWith("/") && isComparable(contextUri)
                    ? contextUri
                    : await vscode.window.showOpenDialog({
                        canSelectMany: false,
                        filters,
                        title: "Compare Files — Select First File",
                    }).then(uris => uris?.[0]);
                if (!left) return;

                // Right file: always prompt.
                right = await vscode.window.showOpenDialog({
                    canSelectMany: false,
                    filters,
                    title: `Compare With (against ${path.basename(left.fsPath)})`,
                    defaultUri: vscode.Uri.file(path.dirname(left.fsPath)),
                }).then(uris => uris?.[0]);
                if (!right) return;
            }
            // Hexana's own entry opens on the structural diff, as it did before the hex diff
            // existed; VS Code's Compare Selected opens on the hex diff (see below).
            await WasmEditorProvider.openDiff(left, right, "hexana");
        };
        return vscode.Disposable.from(
            vscode.commands.registerCommand("hexana.compareFiles", handler),
            vscode.commands.registerCommand("hexana.compareWasm", handler),
            vscode.commands.registerCommand("hexana.compareBinary", handler),
        );
    }

    /**
     * Redirects VS Code's own binary diffs — Compare Selected, Select for Compare, and any
     * other `vscode.diff` of two binaries Hexana opens — to Hexana's diff tab, opened on
     * the hex diff. VS Code would show the two sides as unrelated editors (or "binary file
     * not shown"); a diff tab of two Hexana binaries is closed and replaced as it appears.
     *
     * Only sides VS Code can read through its file system are taken over: a diff whose
     * content comes from a text-document provider (e.g. a `git:` revision) is left alone.
     */
    public static registerCompareSelectedRedirect(): vscode.Disposable {
        return vscode.window.tabGroups.onDidChangeTabs(async (event) => {
            for (const tab of event.opened) {
                const input = tab.input;
                if (input instanceof vscode.TabInputTextDiff) {
                    // Files VS Code shows as text: it reports both sides.
                    const { original, modified } = input;
                    if (await WasmEditorProvider.isHexanaBinary(original) && await WasmEditorProvider.isHexanaBinary(modified)) {
                        await WasmEditorProvider.replaceDiffTab(tab, original, modified);
                    }
                } else if (input === undefined && tab.label.includes(DIFF_LABEL_SEPARATOR)) {
                    // Files the Hexana editor claims: VS Code puts two Hexana editors side by side
                    // and reports the tab as unknown, without its sides. They are paired with the
                    // sides just resolved in this editor, see `noteResolvedSide`.
                    pendingDiffTabs.push({ tab, at: Date.now() });
                    await WasmEditorProvider.matchCustomDiffTabs();
                }
            }
        });
    }

    /**
     * Records a side the Hexana editor just resolved, so a custom-editor diff tab appearing
     * alongside it (whose sides VS Code does not report) can be paired with it.
     */
    private static async noteResolvedSide(uri: vscode.Uri): Promise<void> {
        if (uri.scheme === "hexana-module") return;
        recentSides.push({ uri, at: Date.now() });
        await WasmEditorProvider.matchCustomDiffTabs();
    }

    /**
     * Pairs pending unknown `a ↔ b` diff tabs with recently resolved sides by name, then
     * replaces each paired tab with Hexana's diff. Tabs and sides older than a few seconds
     * are dropped: a diff opens both sides at once, and a stale entry would only mismatch.
     */
    private static async matchCustomDiffTabs(): Promise<void> {
        const now = Date.now();
        prune(recentSides, now);
        prune(pendingDiffTabs, now);
        for (const pending of [...pendingDiffTabs]) {
            const pair = pairSidesForLabel(pending.tab.label, recentSides.map(s => s.uri), uri => path.basename(uri.path));
            if (!pair) continue;
            pendingDiffTabs.splice(pendingDiffTabs.indexOf(pending), 1);
            for (const side of pair) {
                const index = recentSides.findIndex(s => s.uri.toString() === side.toString());
                if (index >= 0) recentSides.splice(index, 1);
            }
            await WasmEditorProvider.replaceDiffTab(pending.tab, pair[0], pair[1]);
        }
    }

    private static async replaceDiffTab(tab: vscode.Tab, original: vscode.Uri, modified: vscode.Uri): Promise<void> {
        try {
            await vscode.window.tabGroups.close(tab);
        } catch (err) {
            console.warn("[Hexana] Failed to close the native diff tab:", err);
        }
        await WasmEditorProvider.openDiff(original, modified, "hex");
    }

    /**
     * Whether [uri] is a binary the Hexana editor opens, readable through VS Code's file
     * system: its extension is one the custom editor claims, or — extensionless — its first
     * bytes carry a known binary signature. Text files are never matched.
     */
    private static async isHexanaBinary(uri: vscode.Uri): Promise<boolean> {
        const base = path.basename(uri.path).toLowerCase();
        const ext = base.includes(".") ? base.split(".").pop() ?? "" : "";
        try {
            if (ext !== "" && !CONTENT_TYPED_EXTENSIONS.has(ext)) {
                if (!HEXANA_DIFF_EXTENSIONS.has(ext) && !base.endsWith(".tar.gz")) return false;
                await vscode.workspace.fs.stat(uri);
                return true;
            }
            const bytes = await vscode.workspace.fs.readFile(uri);
            return isNativeBinary(bytes.subarray(0, 64));
        } catch {
            // Not on a file system VS Code can read (e.g. a `git:` text provider).
            return false;
        }
    }

    private static async openDiff(left: vscode.Uri, right: vscode.Uri, mode: DiffMode): Promise<void> {
        const leftData = await vscode.workspace.fs.readFile(left);
        const rightData = await vscode.workspace.fs.readFile(right);
        const leftName = path.basename(left.fsPath);
        const rightName = path.basename(right.fsPath);

        // Build a synthetic URI for the diff tab. Encode the original file paths so the
        // diff can be restored after a VS Code restart without re-prompting the user.
        const diffUri = vscode.Uri.parse(
            `hexana-module:/${leftName} ↔ ${rightName}?diff=true&mode=${mode}&left=${encodeURIComponent(left.toString())}&right=${encodeURIComponent(right.toString())}`,
        );
        const key = diffUri.toString();

        // Store the left side as the "document" bytes so openCustomDocument can serve
        // something, and store the diff payload separately.
        inMemoryModules.set(key, { data: leftData, fileName: `${leftName} ↔ ${rightName}` });
        pendingDiffs.set(key, { leftData, leftName, rightData, rightName, mode });

        try {
            await vscode.commands.executeCommand("vscode.openWith", diffUri, WasmEditorProvider.viewType);
        } catch (err) {
            console.error("[Hexana] Failed to open diff view:", err);
            vscode.window.showErrorMessage(`Failed to open diff view: ${err}`);
        }
    }

    public static register(context: vscode.ExtensionContext): vscode.Disposable {
        // Register a virtual file-system for the hexana-module scheme so
        // that vscode.openWith can resolve nested-module URIs.
        context.subscriptions.push(
            vscode.workspace.registerFileSystemProvider("hexana-module", new HexanaModuleFS(), {
                isReadonly: true,
            }),
        );

        // Register the content provider for virtual WAT documents so that
        // VS Code's built-in Monaco editor can display WAT text.
        context.subscriptions.push(
            vscode.workspace.registerTextDocumentContentProvider("hexana-wat", watContentProvider),
        );

        const provider = new WasmEditorProvider(context);
        context.subscriptions.push(WasmEditorProvider.registerConfigureRuntimesCommand(provider));
        context.subscriptions.push(WasmEditorProvider.registerConfigureFileAssociationsCommand(provider));

        // One provider instance behind both view types: which one VS Code resolved
        // through only decides whether the editor was picked automatically or by hand,
        // and the provider behaves identically either way.
        return vscode.Disposable.from(
            ...[WasmEditorProvider.viewType, WasmEditorProvider.anyFileViewType].map((viewType) =>
                vscode.window.registerCustomEditorProvider(
                    viewType,
                    provider,
                    {
                        supportsMultipleEditorsPerDocument: true,
                        webviewOptions: { retainContextWhenHidden: true },
                    },
                ),
            ),
        );
    }

    /** Shared by every open editor, so N webviews do not each hit the network. */
    private readonly featureCatalog: WasmFeatureCatalogProvider;

    /**
     * Live webviews and the document each one shows, so a settings change or a catalog
     * refresh reaches all of them - and so a runtime-target push can be routed to the
     * workspace folder the panel's own file lives in.
     */
    private readonly openWebviews = new Map<vscode.WebviewPanel, vscode.Uri>();

    /** Webviews that have been sent their binary, and so can act on a `select-table-row`. */
    private readonly loadedWebviews = new WeakSet<vscode.WebviewPanel>();

    /**
     * Table rows to select in an editor that is still opening, keyed by document URI. Posted
     * right after the binary once its webview reports `ready`; see `openZipEntry`.
     */
    private readonly pendingTableRowSelections = new Map<string, TableRowSelection>();

    /** One per workspace folder, rebuilt whenever the folders change; see `watchRuntimeTargets`. */
    private runtimeTargetsWatchers: vscode.FileSystemWatcher[] = [];

    constructor(private readonly context: vscode.ExtensionContext) {
        this.featureCatalog = new WasmFeatureCatalogProvider(context);
        context.subscriptions.push(this.featureCatalog);

        context.subscriptions.push(
            this.featureCatalog.onDidChange((json) => {
                this.broadcast({ type: "feature-catalog-loaded", featureCatalogJson: json });
            }),
        );

        // An edit by hand, a git pull, or a teammate's commit must take effect without
        // reopening the editor - the point of a project file over per-user settings.
        this.watchRuntimeTargets();
        // The watchers are bound to specific folders, so they have to be rebuilt when the set
        // of folders changes - otherwise a folder opened after activation stays unwatched.
        context.subscriptions.push(
            vscode.workspace.onDidChangeWorkspaceFolders(() => {
                this.watchRuntimeTargets();
                void this.pushRuntimeTargets();
                void this.pushFileAssociations();
                void this.pushProviderScripts();
            }),
        );
        // Trusting the workspace is what lets provider scripts run; nothing else changes.
        context.subscriptions.push(
            vscode.workspace.onDidGrantWorkspaceTrust(() => void this.pushProviderScripts()),
        );
        // Registered once, rather than per rebuild, so repeated folder changes do not pile up
        // dead disposables in the extension context.
        context.subscriptions.push({ dispose: () => this.disposeRuntimeTargetsWatchers() });
    }

    /** Re-arms one `.hexana/runtimes.json` watcher per current workspace folder. */
    private watchRuntimeTargets(): void {
        this.disposeRuntimeTargetsWatchers();

        for (const folder of vscode.workspace.workspaceFolders ?? []) {
            const watcher = vscode.workspace.createFileSystemWatcher(
                new vscode.RelativePattern(folder, `${HEXANA_PROJECT_DIR}/${RUNTIME_TARGETS_FILE}`),
            );
            const push = () => void this.pushRuntimeTargets();
            watcher.onDidCreate(push);
            watcher.onDidChange(push);
            // Deleting the file is a change too: it means "nothing declared", and the webview
            // has to drop the warnings rather than keep showing the last declaration it saw.
            watcher.onDidDelete(push);
            this.runtimeTargetsWatchers.push(watcher);

            // Same lifecycle for the per-file parser choices: an edit by hand or a checkout
            // reparses every open editor the change affects.
            const associationsWatcher = vscode.workspace.createFileSystemWatcher(
                new vscode.RelativePattern(folder, `${HEXANA_PROJECT_DIR}/${FILE_ASSOCIATIONS_FILE}`),
            );
            const pushAssociations = () => void this.pushFileAssociations();
            associationsWatcher.onDidCreate(pushAssociations);
            associationsWatcher.onDidChange(pushAssociations);
            associationsWatcher.onDidDelete(pushAssociations);
            this.runtimeTargetsWatchers.push(associationsWatcher);

            // And for the provider scripts: an edit re-evaluates them in every open editor.
            const scriptsWatcher = vscode.workspace.createFileSystemWatcher(
                new vscode.RelativePattern(folder, `${HEXANA_PROJECT_DIR}/${PROVIDER_SCRIPTS_DIR}/*.js`),
            );
            const pushScripts = () => void this.pushProviderScripts();
            scriptsWatcher.onDidCreate(pushScripts);
            scriptsWatcher.onDidChange(pushScripts);
            scriptsWatcher.onDidDelete(pushScripts);
            this.runtimeTargetsWatchers.push(scriptsWatcher);
        }
    }

    private disposeRuntimeTargetsWatchers(): void {
        for (const watcher of this.runtimeTargetsWatchers) {
            watcher.dispose();
        }
        this.runtimeTargetsWatchers = [];
    }

    /**
     * Sends every open webview the declaration that governs its own file.
     *
     * Read once per folder rather than once per panel: several editors on the same project is
     * the normal case, and they all read the same file.
     */
    private async pushRuntimeTargets(): Promise<void> {
        const byFolder = new Map<string, Promise<string>>();
        for (const [panel, uri] of this.openWebviews) {
            const key = runtimeTargetsFolder(uri)?.uri.toString() ?? "";
            let json = byFolder.get(key);
            if (json === undefined) {
                json = readRuntimeTargetsJson(uri);
                byFolder.set(key, json);
            }
            void panel.webview.postMessage({
                type: "runtime-targets-loaded",
                runtimeTargetsJson: await json,
            });
        }
    }

    /** Sends every open webview the provider scripts that govern its own file. */
    private async pushProviderScripts(): Promise<void> {
        const byFolder = new Map<string, Promise<{ scripts: ProviderScriptSource[]; untrusted: boolean }>>();
        for (const [panel, uri] of this.openWebviews) {
            const key = runtimeTargetsFolder(uri)?.uri.toString() ?? "";
            let loaded = byFolder.get(key);
            if (loaded === undefined) {
                loaded = readProviderScripts(uri);
                byFolder.set(key, loaded);
            }
            const { scripts, untrusted } = await loaded;
            void panel.webview.postMessage({
                type: "provider-scripts-loaded",
                providerScripts: scripts,
                providerScriptsUntrusted: untrusted,
            });
        }
    }

    /** Sends every open webview the file associations that govern its own file. */
    private async pushFileAssociations(): Promise<void> {
        const byFolder = new Map<string, Promise<string>>();
        for (const [panel, uri] of this.openWebviews) {
            const key = runtimeTargetsFolder(uri)?.uri.toString() ?? "";
            let json = byFolder.get(key);
            if (json === undefined) {
                json = readFileAssociationsJson(uri);
                byFolder.set(key, json);
            }
            void panel.webview.postMessage({
                type: "file-associations-loaded",
                fileAssociationsJson: await json,
            });
        }
    }

    private broadcast(message: Record<string, unknown>): void {
        for (const panel of this.openWebviews.keys()) {
            void panel.webview.postMessage(message);
        }
    }

    async openCustomDocument(
        uri: vscode.Uri,
        _openContext: vscode.CustomDocumentOpenContext,
        _token: vscode.CancellationToken,
    ): Promise<WasmDocument> {
        const key = uri.toString();
        const inMemory = inMemoryModules.get(key);
        if (inMemory) {
            // An archive entry can itself be gzipped, so it goes through the same
            // inflation as a file opened from disk; without it the webview would be
            // handed a gzip stream and resolve it to a plain hex view.
            const entry = inflateIfGzipped(inMemory.fileName, inMemory.data);
            const d = entry.data;
            const entryName = entry.displayName ?? inMemory.fileName;
            extensionLib?.captureBinaryOpened(
                entryName,
                new Int8Array(d.buffer, d.byteOffset, d.byteLength),
            );
            return new WasmDocument(uri, d, entryName);
        }

        const raw = await vscode.workspace.fs.readFile(uri);
        const inflated = inflateIfGzipped(path.basename(uri.fsPath), raw);
        const data = inflated.data;
        const displayName = inflated.displayName;

        extensionLib?.captureBinaryOpened(
            displayName ?? path.basename(uri.fsPath),
            new Int8Array(data.buffer, data.byteOffset, data.byteLength),
        );
        return new WasmDocument(uri, data, displayName);
    }

    async resolveCustomEditor(
        document: WasmDocument,
        webviewPanel: vscode.WebviewPanel,
        _token: vscode.CancellationToken,
    ): Promise<void> {
        webviewPanel.webview.options = {
            enableScripts: true,
            localResourceRoots: [
                vscode.Uri.joinPath(this.context.extensionUri, "media"),
            ],
        };

        webviewPanel.webview.html = getWebviewContent(webviewPanel.webview, this.context.extensionUri);
        // Possibly one side of a VS Code diff; see `registerCompareSelectedRedirect`.
        void WasmEditorProvider.noteResolvedSide(document.uri);

        this.openWebviews.set(webviewPanel, document.uri);
        webviewPanel.onDidDispose(() => {
            this.openWebviews.delete(webviewPanel);
            this.dropOrphanedTableRowSelection(document.uri.toString());
        });

        // Once the webview signals it is ready, send the binary payload.
        webviewPanel.webview.onDidReceiveMessage(async (message) => {
            if (message.type === "ready") {
                // Check if this is a diff view.
                const diffKey = document.uri.toString();
                const diff = pendingDiffs.get(diffKey);
                if (diff) {
                    pendingDiffs.delete(diffKey);
                    const isNativeDiff = document.uri.query.includes("native=true");
                    webviewPanel.webview.postMessage({
                        type: isNativeDiff ? "load-native-diff" : "load-diff",
                        leftData: toWebviewBytes(diff.leftData),
                        leftName: diff.leftName,
                        rightData: toWebviewBytes(diff.rightData),
                        rightName: diff.rightName,
                        mode: diff.mode,
                    });
                    return;
                }

                // Diff URI whose payload was lost (e.g. after VS Code restart).
                // Try to reload from disk using the file paths encoded in the URI.
                if (document.uri.query.includes("diff=true")) {
                    const params = new URLSearchParams(document.uri.query);
                    const isNativeDiff = params.get("native") === "true";
                    const leftUriStr = params.get("left");
                    const rightUriStr = params.get("right");
                    if (leftUriStr && rightUriStr) {
                        try {
                            const leftUri = vscode.Uri.parse(leftUriStr);
                            const rightUri = vscode.Uri.parse(rightUriStr);
                            const leftData = await vscode.workspace.fs.readFile(leftUri);
                            const rightData = await vscode.workspace.fs.readFile(rightUri);
                            webviewPanel.webview.postMessage({
                                type: isNativeDiff ? "load-native-diff" : "load-diff",
                                leftData: toWebviewBytes(leftData),
                                leftName: path.basename(leftUri.fsPath),
                                rightData: toWebviewBytes(rightData),
                                rightName: path.basename(rightUri.fsPath),
                                // A tab restored from before the hex diff carries no mode; only
                                // "Hexana: Compare Files" opened diffs then, on this view.
                                mode: params.get("mode") === "hex" ? "hex" : "hexana",
                            });
                            return;
                        } catch (err) {
                            console.warn("[Hexana] Failed to reload diff files from disk:", err);
                        }
                    }
                    webviewPanel.webview.postMessage({
                        type: "load-expired",
                        message: "This diff session has expired and the original files could not be read. Please run \"Hexana: Compare Files\" again.",
                    });
                    return;
                }

                // Expired nested module or zip entry — try to reload from
                // the parent file using the metadata encoded in the URI.
                if (document.uri.scheme === "hexana-module" && document.data.byteLength === 0) {
                    const reloaded = await this.tryReloadExpiredModule(document.uri);
                    if (reloaded) {
                        // Re-populate in-memory store so subsequent reopens
                        // within this session don't need to re-extract.
                        const key = document.uri.toString();
                        const displayName = document.displayName ?? document.uri.path.replace(/^\//, "");
                        inMemoryModules.set(key, { data: reloaded, fileName: displayName });
                        const reloadMsg: Record<string, unknown> = {
                            type: "load-wasm",
                            fileName: displayName,
                            filePath: document.uri.fsPath,
                            data: toWebviewBytes(reloaded),
                            wasmRuntime: getSelectedRuntime(),
                            showSemanticHighlighting: vscode.workspace.getConfiguration("hexana").get<boolean>("showSemanticHighlighting", true),
                        };
                        const reloadParent = this.resolveParentFileName(document.uri);
                        if (reloadParent) {
                            reloadMsg.parentFileName = reloadParent;
                        }
                        webviewPanel.webview.postMessage(reloadMsg);
                        this.markLoaded(document, webviewPanel);
                        return;
                    }

                    // For nested modules (archive/component members) we
                    // cannot extract on the TS side — send the parent bytes
                    // to the webview and let Kotlin re-extract the member.
                    const nestedReloaded = await this.tryReloadNestedModule(document.uri, webviewPanel);
                    if (nestedReloaded) {
                        return;
                    }

                    webviewPanel.webview.postMessage({
                        type: "load-expired",
                        message: "This session has expired and the original content could not be restored. Please reopen from the parent file.",
                    });
                    return;
                }

                const payload = toWebviewBytes(document.data);
                const loadMsg: Record<string, unknown> = {
                    type: "load-wasm",
                    fileName: document.displayName ?? document.uri.fsPath,
                    filePath: document.uri.fsPath,
                    data: payload,
                    wasmRuntime: getSelectedRuntime(),
                    showSemanticHighlighting: vscode.workspace.getConfiguration("hexana").get<boolean>("showSemanticHighlighting", true),
                    featureCatalogJson: this.featureCatalog.get(),
                    runtimeTargetsJson: await readRuntimeTargetsJson(document.uri),
                    fileAssociationsJson: await readFileAssociationsJson(document.uri),
                    fileAssociationKey: fileAssociationKey(document.uri),
                };
                const { scripts: providerScripts, untrusted } = await readProviderScripts(document.uri);
                loadMsg.providerScripts = providerScripts;
                loadMsg.providerScriptsUntrusted = untrusted;
                if (document.displayName && document.displayName !== path.basename(document.uri.fsPath)) {
                    loadMsg.decompressed = true;
                }
                const parentFileName = this.resolveParentFileName(document.uri);
                if (parentFileName) {
                    loadMsg.parentFileName = parentFileName;
                }
                // Run on an Android emulator: on-disk top-level APKs only, when the SDK
                // already has adb and the emulator package (file checks, no processes).
                if (!parentFileName && document.uri.scheme === "file" &&
                    document.uri.fsPath.toLowerCase().endsWith(".apk") && detectAndroidSdk()) {
                    loadMsg.canRunApk = true;
                }
                webviewPanel.webview.postMessage(loadMsg);
                this.markLoaded(document, webviewPanel);
                this.restoreScriptTabs(document, webviewPanel);
                this.restoreSslKeyLog(document, webviewPanel);
            } else if (message.type === "open-module") {
                this.openNestedModule(document, message);
            } else if (message.type === "navigate-to-parent") {
                this.navigateToParent(document);
            } else if (message.type === "run-export") {
                runExport(document, message);
            } else if (message.type === "run-apk") {
                runApkOnEmulator(document.uri.fsPath);
            } else if (message.type === "open-zip-entry") {
                this.openZipEntry(document, message);
            } else if (message.type === "open-tar-entry") {
                this.openTarEntry(document, message);
            } else if (message.type === "show-wat") {
                this.showWatEditor(document, message);
            } else if (message.type === "open-text-file") {
                this.openTextFile(message.fileName as string, message.content as string);
            } else if (message.type === "tab-selected") {
                extensionLib?.captureHexTabSelected(message.tabName, "wasm");
            } else if (message.type === "update-runtime-setting") {
                const runtime = message.runtime as string;
                if (runtime) {
                    vscode.workspace.getConfiguration("hexana").update(
                        "wasmRuntime",
                        runtime,
                        vscode.ConfigurationTarget.Global,
                    );
                }
            } else if (message.type === "save-runtime-targets") {
                await this.saveRuntimeTargets(
                    webviewPanel,
                    message.json as string,
                    message.schema as string | undefined,
                );
            } else if (message.type === "open-file-associations-file") {
                await this.openFileAssociationsFile(
                    webviewPanel,
                    message.json as string,
                    message.schema as string | undefined,
                );
            } else if (message.type === "save-file-associations") {
                await this.saveFileAssociations(
                    webviewPanel,
                    message.json as string,
                    message.schema as string | undefined,
                );
            } else if (message.type === "save-as") {
                this.saveAs(document);
            } else if (message.type === "overwrite-file") {
                this.overwriteFile(document, message);
            } else if (message.type === "save-copy") {
                this.saveCopy(document, message);
            } else if (message.type === "open-url") {
                const url = message.url as string;
                if (url) {
                    vscode.env.openExternal(vscode.Uri.parse(url));
                }
            } else if (message.type === "open-http-body") {
                this.openHttpBody(document, message as any);
            } else if (message.type === "http-replay-send") {
                const reply = await sendHttpReplay(message as HttpReplaySendMessage);
                webviewPanel.webview.postMessage({ ...reply, body: toWebviewBytes(reply.body) });
            } else if (message.type === "open-http-request-file") {
                this.openHttpRequestFile(message as HttpRequestFileMessage);
            } else if (message.type === "open-dts") {
                this.openDtsReference(message.raw === true);
            } else if (message.type === "open-script-for-editing") {
                this.openScriptForEditing(
                    message.scriptPath as string,
                    message.jsConfigContent as string | undefined,
                );
            } else if (message.type === "create-script-from-template") {
                this.createScriptFromTemplate(
                    webviewPanel,
                    message.fileName as string,
                    message.template as string | undefined,
                    message.jsConfigContent as string | undefined,
                );
            } else if (message.type === "pick-script-file") {
                this.pickScriptFile(document, webviewPanel);
            } else if (message.type === "pick-ssl-key-log") {
                this.pickSslKeyLog(document, webviewPanel);
            } else if (message.type === "read-script-file") {
                this.readScriptFile(message.scriptPath as string, webviewPanel);
            } else if (message.type === "read-binary-file") {
                this.readBinaryFile(
                    message.requestId as string,
                    message.path as string,
                    webviewPanel,
                );
            } else if (message.type === "script-tab-added") {
                this.addScriptTabToState(document, message.scriptPath as string);
            } else if (message.type === "script-tab-removed") {
                this.removeScriptTabFromState(document, message.scriptPath as string);
            }
        });

        // Push semantic highlighting setting changes to the webview in real time.
        vscode.workspace.onDidChangeConfiguration((e) => {
            if (e.affectsConfiguration("hexana.showSemanticHighlighting")) {
                const enabled = vscode.workspace.getConfiguration("hexana").get<boolean>("showSemanticHighlighting", true);
                webviewPanel.webview.postMessage({
                    type: "update-setting",
                    key: "showSemanticHighlighting",
                    value: enabled,
                });
            }
        });
    }

    /**
     * Opens the WAT text in a native VS Code editor tab (Monaco) so that the
     * user gets syntax highlighting, search, and all standard editor features.
     */
    private async showWatEditor(
        document: WasmDocument,
        message: { watText: string; warning?: string },
    ): Promise<void> {
        const baseName = document.displayName ?? path.basename(document.uri.fsPath, ".wasm");
        const watUri = vscode.Uri.parse(
            `hexana-wat:/${baseName}.wat?source=${encodeURIComponent(document.uri.toString())}`,
        );

        watDocuments.set(watUri.toString(), message.watText);
        watContentProvider.update(watUri);

        extensionLib?.captureWatViewActivated();

        const doc = await vscode.workspace.openTextDocument(watUri);
        await vscode.window.showTextDocument(doc, {
            viewColumn: vscode.ViewColumn.Beside,
            preserveFocus: false,
            preview: true,
        });

        // Set language to WAT for syntax highlighting.
        await vscode.languages.setTextDocumentLanguage(doc, "wat");

        if (message.warning) {
            vscode.window.showWarningMessage(`WAT conversion warning: ${message.warning}`);
        }
    }

    /**
     * Opens an in-memory text document beside the current editor.
     * Used by the SBOM "View raw JSON" action to surface the embedded JSON.
     */
    private async openTextFile(fileName: string, content: string): Promise<void> {
        const ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        const language = ext === "json" ? "json" : ext || undefined;
        const doc = await vscode.workspace.openTextDocument({ content, language });
        await vscode.window.showTextDocument(doc, {
            viewColumn: vscode.ViewColumn.Beside,
            preserveFocus: false,
            preview: true,
        });
    }

    /**
     * Navigates back to the parent component that contains this submodule.
     *
     * The parent URI is encoded in the `?parent=` query parameter of the
     * nested module's synthetic `hexana-module:` URI.
     */
    private async navigateToParent(document: WasmDocument): Promise<void> {
        const params = new URLSearchParams(document.uri.query);
        const parentKey = params.get("parent");
        if (!parentKey) {
            return;
        }
        try {
            const parentUri = vscode.Uri.parse(decodeURIComponent(parentKey));
            await vscode.commands.executeCommand(
                "vscode.openWith",
                parentUri,
                WasmEditorProvider.viewType,
            );
        } catch (err) {
            console.error("[Hexana] Failed to navigate to parent:", err);
        }
    }

    /**
     * Attempts to reload an expired `hexana-module:` URI by reading the
     * parent file and re-extracting the nested content.
     *
     * Supports two kinds of nested URIs:
     * - Zip entries: `?parent=<uri>&entry=<name>` — scans the parent zip
     *   for the local file header matching the entry name and decompresses.
     * - Nested modules: `?parent=<uri>&index=<n>` — not re-extractable on
     *   the TS side (requires Kotlin parsing), so this falls back to `null`.
     *
     * The parent itself may be an expired `hexana-module:` URI, in which
     * case this method recurses to resolve the full chain.
     *
     * @returns the extracted bytes, or `null` if recovery is not possible.
     */
    private async tryReloadExpiredModule(uri: vscode.Uri): Promise<Uint8Array | null> {
        const params = new URLSearchParams(uri.query);
        const parentKey = params.get("parent");
        if (!parentKey) {
            return null;
        }

        // Resolve parent bytes — may itself be an expired in-memory URI.
        let parentBytes: Uint8Array;
        const parentUri = vscode.Uri.parse(decodeURIComponent(parentKey));
        try {
            const existing = inMemoryModules.get(parentUri.toString());
            if (existing && existing.data.byteLength > 0) {
                parentBytes = existing.data;
            } else if (parentUri.scheme === "hexana-module") {
                // Recursively reload the parent.
                const resolved = await this.tryReloadExpiredModule(parentUri);
                if (!resolved) {
                    return null;
                }
                parentBytes = resolved;
                // Cache so we don't re-extract on every child.
                const parentName = parentUri.path.replace(/^\//, "");
                inMemoryModules.set(parentUri.toString(), { data: parentBytes, fileName: parentName });
            } else {
                parentBytes = await vscode.workspace.fs.readFile(parentUri);
            }
        } catch {
            return null;
        }

        // Zip entry: scan the parent for the local file header by entry name.
        const entryName = params.get("entry");
        if (entryName) {
            return this.extractZipEntryByName(parentBytes, decodeURIComponent(entryName));
        }

        // Nested module (wasm component / archive member): the extraction
        // logic lives in Kotlin and is not available on the TS side.
        // We cannot re-extract here, so return null.
        return null;
    }

    /**
     * Handles expired nested module URIs (with `?parent=...&index=N`) by
     * reading the parent bytes and sending a `load-nested-module` message
     * to the webview, which uses Kotlin-side parsing to extract the member.
     *
     * @returns `true` if the message was sent, `false` if recovery failed.
     */
    private async tryReloadNestedModule(
        uri: vscode.Uri,
        webviewPanel: vscode.WebviewPanel,
    ): Promise<boolean> {
        const params = new URLSearchParams(uri.query);
        const parentKey = params.get("parent");
        const indexStr = params.get("index");
        if (!parentKey || indexStr == null) {
            return false;
        }
        const moduleIndex = parseInt(indexStr, 10);
        if (isNaN(moduleIndex)) {
            return false;
        }

        // Resolve parent bytes.
        let parentBytes: Uint8Array;
        const parentUri = vscode.Uri.parse(decodeURIComponent(parentKey));
        try {
            const existing = inMemoryModules.get(parentUri.toString());
            if (existing && existing.data.byteLength > 0) {
                parentBytes = existing.data;
            } else if (parentUri.scheme === "hexana-module") {
                const resolved = await this.tryReloadExpiredModule(parentUri);
                if (!resolved) {
                    return false;
                }
                parentBytes = resolved;
                const parentName = parentUri.path.replace(/^\//, "");
                inMemoryModules.set(parentUri.toString(), { data: parentBytes, fileName: parentName });
            } else {
                parentBytes = await vscode.workspace.fs.readFile(parentUri);
            }
        } catch {
            return false;
        }

        const displayName = uri.path.replace(/^\//, "");
        webviewPanel.webview.postMessage({
            type: "load-nested-module",
            parentData: toWebviewBytes(parentBytes),
            moduleIndex,
            fileName: displayName,
            wasmRuntime: getSelectedRuntime(),
        });
        return true;
    }

    /**
     * Scans a zip archive for a local file header matching the given entry
     * name and returns the decompressed bytes, or `null` on failure.
     */
    private extractZipEntryByName(archive: Uint8Array, entryName: string): Uint8Array | null {
        // Scan for local file headers (PK\x03\x04).
        const nameBytes = new TextEncoder().encode(entryName);
        for (let i = 0; i <= archive.length - 30; i++) {
            if (archive[i] !== 0x50 || archive[i + 1] !== 0x4B ||
                archive[i + 2] !== 0x03 || archive[i + 3] !== 0x04) {
                continue;
            }
            const compressionMethod = archive[i + 8] | (archive[i + 9] << 8);
            const compressedSize = archive[i + 18] | (archive[i + 19] << 8) |
                (archive[i + 20] << 16) | (archive[i + 21] << 24);
            const nameLen = archive[i + 26] | (archive[i + 27] << 8);
            const extraLen = archive[i + 28] | (archive[i + 29] << 8);

            if (i + 30 + nameLen > archive.length) {
                continue;
            }

            // Compare entry name.
            if (nameLen !== nameBytes.length) {
                continue;
            }
            let match = true;
            for (let j = 0; j < nameLen; j++) {
                if (archive[i + 30 + j] !== nameBytes[j]) {
                    match = false;
                    break;
                }
            }
            if (!match) {
                continue;
            }

            const dataOffset = i + 30 + nameLen + extraLen;
            try {
                const compressed = archive.slice(dataOffset, dataOffset + compressedSize);
                if (compressionMethod === 0) {
                    return compressed;
                } else if (compressionMethod === 8) {
                    return zlib.inflateRawSync(compressed);
                }
            } catch {
                return null;
            }
        }
        return null;
    }

    /**
     * Handles an `open-tar-entry` message from the webview by slicing the
     * raw bytes from the archive and opening the result in a new editor tab.
     * TAR entries are stored uncompressed, so no decompression is needed.
     */
    private async openTarEntry(
        document: WasmDocument,
        message: {
            name: string;
            dataOffset: number;
            size: number;
        },
    ): Promise<void> {
        const { name, dataOffset, size } = message;
        const childName = name.includes("/") ? name.substring(name.lastIndexOf("/") + 1) : name;
        if (!childName) {
            return;
        }

        const bytes = document.data.slice(dataOffset, dataOffset + size);

        const ext = childName.includes(".") ? childName.substring(childName.lastIndexOf(".") + 1).toLowerCase() : "";

        if (OPENABLE_ENTRY_EXTENSIONS.has(ext) || isNativeBinary(bytes)) {
            const parentKey = document.uri.toString();
            const moduleUri = vscode.Uri.parse(
                `hexana-module:/${childName}?parent=${encodeURIComponent(parentKey)}&entry=${encodeURIComponent(name)}`,
            );
            const key = moduleUri.toString();
            inMemoryModules.set(key, { data: bytes, fileName: childName });

            try {
                await vscode.commands.executeCommand(
                    "vscode.openWith",
                    moduleUri,
                    WasmEditorProvider.viewType,
                );
            } catch (err) {
                console.error("[Hexana] Failed to open tar entry:", err);
                vscode.window.showErrorMessage(`Failed to open "${childName}": ${err}`);
            }
        } else {
            const content = new TextDecoder("utf-8").decode(bytes);
            const doc = await vscode.workspace.openTextDocument({ content, language: ext || undefined });
            await vscode.window.showTextDocument(doc, { preview: true });
        }
    }

    /**
     * Records that [webviewPanel] has its binary, and posts the row selection waiting for its
     * document, if any. Messages a webview receives are handled in order, so the selection
     * lands after the payload it refers to.
     */
    private markLoaded(document: WasmDocument, webviewPanel: vscode.WebviewPanel): void {
        this.loadedWebviews.add(webviewPanel);
        const key = document.uri.toString();
        const selection = this.pendingTableRowSelections.get(key);
        if (selection) {
            this.pendingTableRowSelections.delete(key);
            void webviewPanel.webview.postMessage({ type: "select-table-row", ...selection });
        }
    }

    /**
     * Drops the row selection waiting for [key] once no open editor can still deliver it — the
     * one opening closed before its binary arrived — so a later open of that entry does not
     * select a stale row.
     */
    private dropOrphanedTableRowSelection(key: string): void {
        if (!this.pendingTableRowSelections.has(key)) return;
        for (const [panel, uri] of this.openWebviews) {
            if (uri.toString() === key && !this.loadedWebviews.has(panel)) return;
        }
        this.pendingTableRowSelections.delete(key);
    }

    /** The open editor showing [key], preferring one whose webview already has its binary. */
    private findOpenWebview(key: string): vscode.WebviewPanel | undefined {
        let found: vscode.WebviewPanel | undefined;
        for (const [panel, uri] of this.openWebviews) {
            if (uri.toString() !== key) continue;
            if (this.loadedWebviews.has(panel)) return panel;
            if (!found) found = panel;
        }
        return found;
    }

    /**
     * Handles an `open-zip-entry` message from the webview by reading the
     * compressed bytes from the archive, decompressing them, and opening the
     * result in an editor tab — or bringing forward the one already showing
     * that entry. With `selectTabName`/`selectRowIndex` ("Reveal in DEX"),
     * that editor then selects the row of its table tab.
     */
    private async openZipEntry(
        document: WasmDocument,
        message: {
            name: string;
            dataOffset: number;
            compressedSize: number;
            uncompressedSize: number;
            compressionMethod: number;
            selectTabName?: string;
            selectRowIndex?: number;
        },
    ): Promise<void> {
        const { name, dataOffset, compressedSize, uncompressedSize, compressionMethod } = message;
        const childName = name.includes("/") ? name.substring(name.lastIndexOf("/") + 1) : name;
        if (!childName) {
            return;
        }
        const selection: TableRowSelection | undefined =
            typeof message.selectTabName === "string" && typeof message.selectRowIndex === "number"
                ? { tabName: message.selectTabName, rowIndex: message.selectRowIndex }
                : undefined;

        // An entry already open from this archive is brought forward, not opened twice.
        const entryKey = zipEntryModuleUri(childName, document.uri, name).toString();
        const existing = this.findOpenWebview(entryKey);
        if (existing) {
            existing.reveal(existing.viewColumn);
            if (selection) {
                if (this.loadedWebviews.has(existing)) {
                    void existing.webview.postMessage({ type: "select-table-row", ...selection });
                } else {
                    this.pendingTableRowSelections.set(entryKey, selection);
                }
            }
            return;
        }

        let bytes: Uint8Array;
        try {
            const compressed = document.data.slice(dataOffset, dataOffset + compressedSize);
            if (compressionMethod === 0) {
                // Stored — no compression.
                bytes = compressed;
            } else if (compressionMethod === 8) {
                // Deflate — use raw inflate (no zlib/gzip header).
                bytes = zlib.inflateRawSync(compressed);
            } else {
                vscode.window.showWarningMessage(
                    `Unsupported compression method ${compressionMethod} for "${name}".`,
                );
                return;
            }
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to decompress "${name}": ${err}`);
            return;
        }

        // Check if the entry is a binary format we handle (wasm, class, jar, etc.)
        const ext = childName.includes(".") ? childName.substring(childName.lastIndexOf(".") + 1).toLowerCase() : "";

        if (OPENABLE_ENTRY_EXTENSIONS.has(ext) || isNativeBinary(bytes)) {
            // Open as a binary in our custom editor.
            const moduleUri = zipEntryModuleUri(childName, document.uri, name);
            const key = moduleUri.toString();
            inMemoryModules.set(key, { data: bytes, fileName: childName });
            if (selection) {
                this.pendingTableRowSelections.set(key, selection);
            }

            try {
                await vscode.commands.executeCommand(
                    "vscode.openWith",
                    moduleUri,
                    WasmEditorProvider.viewType,
                );
            } catch (err) {
                this.pendingTableRowSelections.delete(key);
                console.error("[Hexana] Failed to open zip entry:", err);
                vscode.window.showErrorMessage(`Failed to open "${childName}": ${err}`);
            }
        } else {
            // Open as a text document.
            const content = new TextDecoder("utf-8").decode(bytes);
            const doc = await vscode.workspace.openTextDocument({ content, language: ext || undefined });
            await vscode.window.showTextDocument(doc, { preview: true });
        }
    }

    /**
     * Overwrites the original file with the edited bytes sent from the webview.
     */
    private async overwriteFile(
        document: WasmDocument,
        message: { data: number[] },
    ): Promise<void> {
        // In-memory modules (nested/submodules) cannot be overwritten.
        if (document.uri.scheme !== "file") {
            vscode.window.showWarningMessage("Cannot overwrite a virtual or in-memory document.");
            return;
        }
        try {
            const bytes = new Uint8Array(message.data);
            await vscode.workspace.fs.writeFile(document.uri, bytes);
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to overwrite file: ${err}`);
        }
    }

    /**
     * Saves a copy of the edited bytes as a sibling file next to the original.
     */
    private async saveCopy(
        document: WasmDocument,
        message: { outputName: string; data: number[] },
    ): Promise<void> {
        if (document.uri.scheme !== "file") {
            vscode.window.showWarningMessage("Cannot save a copy for a virtual document.");
            return;
        }
        try {
            const bytes = new Uint8Array(message.data);
            const parentUri = vscode.Uri.joinPath(document.uri, "..");
            const targetUri = vscode.Uri.joinPath(parentUri, message.outputName);
            await vscode.workspace.fs.writeFile(targetUri, bytes);
            vscode.window.showInformationMessage(`Saved copy to ${message.outputName}`);
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to save copy: ${err}`);
        }
    }

    /**
     * Resolves the display name of the parent file from a `hexana-module:` URI's
     * `?parent=` query parameter. Returns `undefined` for non-nested documents.
     */
    private resolveParentFileName(uri: vscode.Uri): string | undefined {
        if (uri.scheme !== "hexana-module") return undefined;
        const params = new URLSearchParams(uri.query);
        const parentKey = params.get("parent");
        if (!parentKey) return undefined;
        try {
            const parentUri = vscode.Uri.parse(decodeURIComponent(parentKey));
            // Check if the parent is also in-memory (has its own display name).
            const parentEntry = inMemoryModules.get(parentUri.toString());
            if (parentEntry?.fileName) return parentEntry.fileName;
            return path.basename(parentUri.fsPath);
        } catch {
            return undefined;
        }
    }

    /**
     * Shows a Save As dialog and writes the current document bytes to the
     * user-chosen location. Used for submodules and archive entries.
     */
    private async saveAs(document: WasmDocument): Promise<void> {
        const defaultName = document.displayName ?? path.basename(document.uri.fsPath);
        const uri = await vscode.window.showSaveDialog({
            defaultUri: vscode.Uri.file(defaultName),
            saveLabel: "Save As",
        });
        if (!uri) return;
        try {
            const bytes = document.data instanceof Buffer
                ? new Uint8Array(document.data.buffer, document.data.byteOffset, document.data.byteLength)
                : document.data;
            await vscode.workspace.fs.writeFile(uri, bytes);
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to save file: ${err}`);
        }
    }

    /**
     * Handles an `open-module` message from the webview by storing the nested
     * module bytes in memory and opening a new custom editor tab for them.
     */
    private async openNestedModule(
        parentDocument: WasmDocument,
        message: { moduleIndex: number; fileName: string; data: number[] },
    ): Promise<void> {
        const bytes = new Uint8Array(message.data);
        const moduleName = message.fileName || `module_${message.moduleIndex}.wasm`;

        // Build a unique URI for this nested module.
        const parentKey = parentDocument.uri.toString();
        const moduleUri = vscode.Uri.parse(
            `hexana-module:/${moduleName}?parent=${encodeURIComponent(parentKey)}&index=${message.moduleIndex}`,
        );

        // Store using the exact same string that openCustomDocument will produce
        // when it calls uri.toString().
        const key = moduleUri.toString();
        inMemoryModules.set(key, { data: bytes, fileName: moduleName });

        try {
            // Open with our custom editor view type.
            await vscode.commands.executeCommand(
                "vscode.openWith",
                moduleUri,
                WasmEditorProvider.viewType,
            );
        } catch (err) {
            console.error("[Hexana] Failed to open nested module:", err);
            vscode.window.showErrorMessage(
                `Failed to open nested module "${moduleName}": ${err}`,
            );
        }
    }

    // -------------------------------------------------------------------
    // HTTP body extraction
    // -------------------------------------------------------------------

    /**
     * Writes a request replayed from a PCAP as an `.http` file and opens it,
     * so the REST Client extension can send it. Without REST Client the file
     * still opens as text, and the user is offered the extension.
     */
    private async openHttpRequestFile(message: HttpRequestFileMessage): Promise<void> {
        try {
            const filePath = await writeHttpRequestFile(message);
            const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(filePath));
            await vscode.window.showTextDocument(doc, {
                viewColumn: vscode.ViewColumn.Beside,
                preserveFocus: false,
            });
            if (!vscode.extensions.getExtension(REST_CLIENT_EXTENSION_ID)) {
                const install = "Show REST Client";
                const choice = await vscode.window.showInformationMessage(
                    "Install the REST Client extension to send this request from the editor.",
                    install,
                );
                if (choice === install) {
                    await vscode.commands.executeCommand("extension.open", REST_CLIENT_EXTENSION_ID);
                }
            }
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to open the HTTP request: ${err}`);
        }
    }

    /**
     * Opens an HTTP response body extracted from a PCAP packet in a new
     * editor tab. The body bytes are sent from the webview as a number
     * array; we decode them and open an untitled document with the
     * appropriate language mode inferred from the Content-Type.
     */
    private async openHttpBody(
        _document: WasmDocument,
        message: { fileName: string; data: number[]; contentType: string; contentEncoding?: string },
    ): Promise<void> {
        try {
            const bytes = this.decompressHttpBody(new Uint8Array(message.data), message.contentEncoding);

            if (this.isTextContentType(message.contentType)) {
                const content = new TextDecoder("utf-8", { fatal: false }).decode(bytes);
                const language = this.contentTypeToLanguageId(message.contentType);
                const doc = await vscode.workspace.openTextDocument({ content, language });
                await vscode.window.showTextDocument(doc, {
                    viewColumn: vscode.ViewColumn.Beside,
                    preserveFocus: false,
                    preview: true,
                });
            } else {
                // Unknown or binary content — open in the Hexana custom editor
                const fileName = message.fileName || "http-body.bin";
                const moduleUri = vscode.Uri.parse(
                    `hexana-module:/${fileName}?source=http-body`,
                );
                const key = moduleUri.toString();
                inMemoryModules.set(key, { data: bytes, fileName });
                await vscode.commands.executeCommand(
                    "vscode.openWith",
                    moduleUri,
                    WasmEditorProvider.viewType,
                );
            }
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to open HTTP body: ${err}`);
        }
    }

    /**
     * Decompresses an HTTP body according to the Content-Encoding header.
     * Supports gzip, x-gzip, and deflate. Returns the original bytes
     * unchanged when the encoding is absent, identity, or unrecognised.
     */
    private decompressHttpBody(bytes: Uint8Array, contentEncoding?: string): Uint8Array {
        if (!contentEncoding || bytes.length === 0) return bytes;
        const encoding = contentEncoding.trim().toLowerCase();
        try {
            switch (encoding) {
                case "gzip":
                case "x-gzip": {
                    const buf = zlib.gunzipSync(bytes);
                    return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
                }
                case "deflate": {
                    // "deflate" can be zlib-wrapped or raw; try zlib first.
                    try {
                        const buf = zlib.inflateSync(bytes);
                        return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
                    } catch {
                        const buf = zlib.inflateRawSync(bytes);
                        return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
                    }
                }
                case "identity":
                    return bytes;
                default:
                    return bytes;
            }
        } catch (err) {
            console.warn(`[Hexana] Failed to decompress HTTP body (${encoding}):`, err);
            return bytes;
        }
    }

    /**
     * Returns `true` when the Content-Type is a known text format that
     * should be opened as a regular text document rather than in Hexana.
     */
    private isTextContentType(contentType: string): boolean {
        if (!contentType) return false;
        const mime = contentType.split(";")[0].trim().toLowerCase();
        if (mime.startsWith("text/")) return true;
        switch (mime) {
            case "application/json":
            case "application/xml":
            case "application/javascript":
            case "application/xhtml+xml":
            case "application/svg+xml":
                return true;
            default:
                return false;
        }
    }

    /**
     * Maps an HTTP Content-Type value to a VS Code language identifier.
     */
    private contentTypeToLanguageId(contentType: string): string {
        if (!contentType) return "plaintext";
        const mime = contentType.split(";")[0].trim().toLowerCase();
        switch (mime) {
            case "application/json": return "json";
            case "application/xml":
            case "text/xml": return "xml";
            case "text/html": return "html";
            case "text/css": return "css";
            case "text/javascript":
            case "application/javascript": return "javascript";
            case "text/plain": return "plaintext";
            default:
                if (mime.startsWith("text/")) return "plaintext";
                return "plaintext";
        }
    }

    // -------------------------------------------------------------------
    // Script tab support
    // -------------------------------------------------------------------

    /**
     * Opens a pre-built scripting reference in a new VS Code editor tab.
     *
     * @param raw when true, opens the format-specific type dump for
     *   `binary.raw` instead of the curated API reference
     */
    private async openDtsReference(raw: boolean = false): Promise<void> {
        try {
            const fileName = raw ? "hexana-scripting-raw.d.ts" : "hexana-scripting.d.ts";
            const dtsPath = path.join(this.context.extensionPath, fileName);
            const dtsUri = vscode.Uri.file(dtsPath);
            const bytes = await vscode.workspace.fs.readFile(dtsUri);
            const content = new TextDecoder("utf-8").decode(bytes);
            const doc = await vscode.workspace.openTextDocument({
                content,
                language: "typescript",
            });
            await vscode.window.showTextDocument(doc, {
                viewColumn: vscode.ViewColumn.Beside,
                preserveFocus: false,
                preview: true,
            });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to open API reference: ${err}`);
        }
    }

    /**
     * Creates a runnable script from the starter template or a bundled example,
     * opens it in an editor, and reports it back to the webview as if the user
     * had picked it in the file dialog.
     *
     * Starting from a blank `.js` file is the main obstacle to writing a script
     * tab, because it requires knowing the API before typing anything. A file
     * that already produces output turns that into editing lines that work.
     *
     * @param fileName file name to create, e.g. `hexana-script.js`
     * @param template inline starter source; when omitted, the bundled example
     *   named [fileName] is copied instead
     */
    private async createScriptFromTemplate(
        webviewPanel: vscode.WebviewPanel,
        fileName: string,
        template?: string,
        jsConfigContent?: string,
    ): Promise<void> {
        try {
            const content = template ?? await this.readBundledExample(fileName);
            const directory = this.scriptsDirectory();
            await vscode.workspace.fs.createDirectory(directory);

            const target = await this.uniqueScriptUri(directory, fileName);
            await vscode.workspace.fs.writeFile(target, new TextEncoder().encode(content));
            await this.attachScriptTypes(directory, jsConfigContent);

            const doc = await vscode.workspace.openTextDocument(target);
            await vscode.window.showTextDocument(doc, {
                viewColumn: vscode.ViewColumn.Beside,
                preserveFocus: true,
                preview: false,
            });
            webviewPanel.webview.postMessage({
                type: "script-file-picked",
                scriptPath: target.fsPath,
                scriptContent: content,
            });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to create script: ${err}`);
        }
    }

    /**
     * Opens an existing script for editing, attaching the type definitions first,
     * so the editor resolves `binary`, `print`, `table` and `row`.
     */
    private async openScriptForEditing(scriptPath: string, jsConfigContent?: string): Promise<void> {
        try {
            const uri = vscode.Uri.file(scriptPath);
            await this.attachScriptTypes(vscode.Uri.file(path.dirname(scriptPath)), jsConfigContent);
            const doc = await vscode.workspace.openTextDocument(uri);
            await vscode.window.showTextDocument(doc, { preview: false });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to open script: ${err}`);
        }
    }

    /**
     * Writes the scripting type definitions into a script's folder.
     *
     * A standalone `.js` file gets no automatic association with a sibling
     * `.d.ts`, so the `jsconfig.json` is what actually puts the types in scope.
     * It is only created when the folder has no JavaScript project config at all —
     * an existing one is the user's and already governs how the folder is
     * analysed. The `.d.ts` itself is rewritten whenever it differs, so it
     * refreshes when the extension updates.
     */
    private async attachScriptTypes(directory: vscode.Uri, jsConfigContent?: string): Promise<void> {
        const encoder = new TextEncoder();
        const decoder = new TextDecoder("utf-8");

        const typesUri = vscode.Uri.joinPath(directory, HEXANA_SCRIPT_TYPES_FILE);
        const expected = await this.readBundledTypes();
        let current: string | undefined;
        try {
            current = decoder.decode(await vscode.workspace.fs.readFile(typesUri));
        } catch {
            current = undefined;
        }
        if (current !== expected) {
            await vscode.workspace.fs.writeFile(typesUri, encoder.encode(expected));
        }

        const hasConfig = await Promise.all(
            [HEXANA_SCRIPT_JSCONFIG_FILE, "tsconfig.json"].map(async (name) => {
                try {
                    await vscode.workspace.fs.stat(vscode.Uri.joinPath(directory, name));
                    return true;
                } catch {
                    return false;
                }
            }),
        );
        if (!hasConfig.some(Boolean) && jsConfigContent) {
            await vscode.workspace.fs.writeFile(
                vscode.Uri.joinPath(directory, HEXANA_SCRIPT_JSCONFIG_FILE),
                encoder.encode(jsConfigContent),
            );
        }
    }

    /** The curated reference shipped with the extension. */
    private async readBundledTypes(): Promise<string> {
        const uri = vscode.Uri.file(path.join(this.context.extensionPath, HEXANA_SCRIPT_TYPES_FILE));
        return new TextDecoder("utf-8").decode(await vscode.workspace.fs.readFile(uri));
    }

    /**
     * Writes the runtime targets edited in the webview to `.hexana/runtimes.json`, then
     * re-reads and pushes it so every open editor agrees on what is on disk.
     *
     * Written to the folder the editing panel's own file lives in, so in a multi-root
     * workspace the declaration lands next to the binary it was made for rather than in
     * whichever folder happens to be first.
     *
     * Needs a workspace folder: without one there is no project to declare targets for, and
     * the webview's `canEditRuntimeTargets` capability is what normally keeps the dialog from
     * being offered at all.
     */
    private async saveRuntimeTargets(
        webviewPanel: vscode.WebviewPanel,
        json: string,
        schema?: string,
    ): Promise<void> {
        const folder = runtimeTargetsFolder(this.openWebviews.get(webviewPanel));
        if (!folder) {
            void vscode.window.showWarningMessage(
                "Open a folder before configuring target runtimes: they are saved to " +
                `${HEXANA_PROJECT_DIR}/${RUNTIME_TARGETS_FILE} in the workspace.`,
            );
            return;
        }
        try {
            const directory = vscode.Uri.joinPath(folder.uri, HEXANA_PROJECT_DIR);
            await vscode.workspace.fs.createDirectory(directory);
            const encoder = new TextEncoder();
            // Both files are written only when they differ: they are committed to version
            // control, and confirming the dialog without changing anything should leave them
            // alone. (The same read-compare-write discipline `RuntimeTargetsStore` uses.)
            // The schema goes first, so the reference the settings file carries never points
            // at a file that is not there yet.
            if (schema) {
                const schemaFile = vscode.Uri.joinPath(directory, RUNTIME_TARGETS_SCHEMA_FILE);
                if (await this.needsWrite(schemaFile, schema)) {
                    await vscode.workspace.fs.writeFile(schemaFile, encoder.encode(schema));
                }
            }
            const file = vscode.Uri.joinPath(directory, RUNTIME_TARGETS_FILE);
            if (await this.needsWrite(file, json)) {
                await vscode.workspace.fs.writeFile(file, encoder.encode(json));
            }
            // The file watcher also fires, but pushing here makes the round-trip immediate for
            // the editor the user is looking at; editors on other folders keep their own.
            await this.pushRuntimeTargets();

        } catch (error) {
            void vscode.window.showErrorMessage(
                `Failed to save target runtimes: ${error instanceof Error ? error.message : String(error)}`,
            );
        }
    }

    /**
     * Writes the file associations rendered by the webview to `.hexana/file_associations.json`
     * (and the schema next to it), then pushes the result to every open editor so each one
     * reparses if its own file's parser changed. Same folder choice and read-compare-write
     * discipline as [saveRuntimeTargets].
     */
    private async saveFileAssociations(
        webviewPanel: vscode.WebviewPanel,
        json: string,
        schema?: string,
    ): Promise<void> {
        const folder = runtimeTargetsFolder(this.openWebviews.get(webviewPanel));
        if (!folder) {
            void vscode.window.showWarningMessage(
                "Open a folder before choosing a parser: the choice is saved to " +
                `${HEXANA_PROJECT_DIR}/${FILE_ASSOCIATIONS_FILE} in the workspace.`,
            );
            return;
        }
        try {
            const directory = vscode.Uri.joinPath(folder.uri, HEXANA_PROJECT_DIR);
            await vscode.workspace.fs.createDirectory(directory);
            const encoder = new TextEncoder();
            if (schema) {
                const schemaFile = vscode.Uri.joinPath(directory, FILE_ASSOCIATIONS_SCHEMA_FILE);
                if (await this.needsWrite(schemaFile, schema)) {
                    await vscode.workspace.fs.writeFile(schemaFile, encoder.encode(schema));
                }
            }
            const file = vscode.Uri.joinPath(directory, FILE_ASSOCIATIONS_FILE);
            if (await this.needsWrite(file, json)) {
                await vscode.workspace.fs.writeFile(file, encoder.encode(json));
            }
            await this.pushFileAssociations();
        } catch (error) {
            void vscode.window.showErrorMessage(
                `Failed to save file parser associations: ${error instanceof Error ? error.message : String(error)}`,
            );
        }
    }

    /**
     * Opens `.hexana/file_associations.json` - from the format badge's "Edit File
     * Associations..." item - in a text editor beside the binary. VS Code has no settings page
     * for it, and with the schema next to it the file completes and validates as it is edited;
     * the watcher then carries a save to every open editor.
     *
     * A missing file is created from [json] and [schema] first. An existing one is left as it
     * is: it is the user's, and may hold edits the webview has not seen yet.
     */
    private async openFileAssociationsFile(
        webviewPanel: vscode.WebviewPanel,
        json: string,
        schema?: string,
    ): Promise<void> {
        const folder = runtimeTargetsFolder(this.openWebviews.get(webviewPanel));
        if (!folder) {
            void vscode.window.showWarningMessage(
                "Open a folder to edit file parser associations: they live in " +
                `${HEXANA_PROJECT_DIR}/${FILE_ASSOCIATIONS_FILE} in the workspace.`,
            );
            return;
        }
        const directory = vscode.Uri.joinPath(folder.uri, HEXANA_PROJECT_DIR);
        const file = vscode.Uri.joinPath(directory, FILE_ASSOCIATIONS_FILE);
        try {
            if (!(await this.exists(file))) {
                await vscode.workspace.fs.createDirectory(directory);
                const encoder = new TextEncoder();
                if (schema) {
                    const schemaFile = vscode.Uri.joinPath(directory, FILE_ASSOCIATIONS_SCHEMA_FILE);
                    if (await this.needsWrite(schemaFile, schema)) {
                        await vscode.workspace.fs.writeFile(schemaFile, encoder.encode(schema));
                    }
                }
                await vscode.workspace.fs.writeFile(file, encoder.encode(json));
            }
            await vscode.window.showTextDocument(file, { viewColumn: vscode.ViewColumn.Beside });
        } catch (error) {
            void vscode.window.showErrorMessage(
                `Failed to open file parser associations: ${error instanceof Error ? error.message : String(error)}`,
            );
        }
    }

    private async exists(file: vscode.Uri): Promise<boolean> {
        try {
            await vscode.workspace.fs.stat(file);
            return true;
        } catch {
            return false;
        }
    }

    /** Whether [file] is missing or holds something other than [content]. */
    private async needsWrite(file: vscode.Uri, content: string): Promise<boolean> {
        try {
            return new TextDecoder("utf-8").decode(await vscode.workspace.fs.readFile(file)) !== content;
        } catch {
            return true;
        }
    }

    /**
     * Directory the created scripts live in: inside the workspace when there is
     * one, otherwise the extension's own storage. Defined once so the file picker
     * can start where the scripts already are.
     */
    private scriptsDirectory(): vscode.Uri {
        const folder = vscode.workspace.workspaceFolders?.[0];
        return folder
            ? vscode.Uri.joinPath(folder.uri, ".hexana", "scripts")
            : vscode.Uri.file(path.join(this.context.globalStorageUri.fsPath, "scripts"));
    }

    /** The scripts directory, or undefined when it does not exist yet. */
    private async existingScriptsDirectory(): Promise<vscode.Uri | undefined> {
        const directory = this.scriptsDirectory();
        try {
            const stat = await vscode.workspace.fs.stat(directory);
            return stat.type === vscode.FileType.Directory ? directory : undefined;
        } catch {
            return undefined;
        }
    }

    private async readBundledExample(fileName: string): Promise<string> {
        const uri = vscode.Uri.file(path.join(this.context.extensionPath, "hexana-scripts", fileName));
        return new TextDecoder("utf-8").decode(await vscode.workspace.fs.readFile(uri));
    }

    /** Never clobbers a script the user may have already edited. */
    private async uniqueScriptUri(directory: vscode.Uri, fileName: string): Promise<vscode.Uri> {
        const extension = path.extname(fileName) || ".js";
        const base = path.basename(fileName, extension);
        for (let index = 1; index < 1000; index++) {
            const candidate = vscode.Uri.joinPath(
                directory,
                index === 1 ? `${base}${extension}` : `${base}-${index}${extension}`,
            );
            try {
                await vscode.workspace.fs.stat(candidate);
            } catch {
                return candidate;
            }
        }
        throw new Error(`Could not find an unused name for ${fileName}`);
    }

    /**
     * Shows a file picker for `.js` files and sends the selected file's
     * path and content back to the webview.
     */
    private async pickScriptFile(
        document: WasmDocument,
        webviewPanel: vscode.WebviewPanel,
    ): Promise<void> {
        const uris = await vscode.window.showOpenDialog({
            canSelectMany: false,
            filters: { "JavaScript": ["js"] },
            title: "Select Script File",
            // Start where this dialog's own scripts are; VS Code falls back to the
            // last used location when the folder does not exist yet.
            defaultUri: await this.existingScriptsDirectory(),
        });
        if (!uris || uris.length === 0) return;
        const uri = uris[0];
        try {
            const bytes = await vscode.workspace.fs.readFile(uri);
            const content = new TextDecoder("utf-8").decode(bytes);
            webviewPanel.webview.postMessage({
                type: "script-file-picked",
                scriptPath: uri.fsPath,
                scriptContent: content,
            });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to read script file: ${err}`);
        }
    }

    /**
     * Shows a file picker for an SSLKEYLOGFILE (NSS Key Log Format) and sends
     * its text content to the webview for TLS decryption of a pcap capture.
     * Cancelling posts nothing.
     */
    private async pickSslKeyLog(
        document: WasmDocument,
        webviewPanel: vscode.WebviewPanel,
    ): Promise<void> {
        const uris = await vscode.window.showOpenDialog({
            canSelectMany: false,
            title: "Select SSL Key Log File",
            filters: {
                "SSL key log": ["txt", "log", "keylog", "sslkeylog"],
                "All files": ["*"],
            },
            // A key log usually sits next to the capture it belongs to, so open
            // there rather than in the last used location.
            defaultUri: vscode.Uri.joinPath(document.uri, ".."),
        });
        if (!uris || uris.length === 0) return;
        const uri = uris[0];
        try {
            const bytes = await vscode.workspace.fs.readFile(uri);
            const content = new TextDecoder("utf-8").decode(bytes);
            // Remember the pairing so reopening the capture decrypts it without
            // a second trip through the picker. Only the path is stored.
            this.context.workspaceState.update(sslKeyLogStateKey(document), uri.toString());
            webviewPanel.webview.postMessage({
                type: "ssl-key-log-picked",
                keyLogPath: uri.fsPath,
                keyLogContent: content,
            });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to read SSL key log file: ${err}`);
        }
    }

    /**
     * Re-sends the SSL key log this capture was last paired with, so a
     * reopened capture decrypts itself. A pairing whose key log has since been
     * deleted or moved is forgotten rather than reported: the user did not ask
     * for anything here, so there is nothing to fail.
     */
    private async restoreSslKeyLog(
        document: WasmDocument,
        webviewPanel: vscode.WebviewPanel,
    ): Promise<void> {
        const key = sslKeyLogStateKey(document);
        const stored = this.context.workspaceState.get<string>(key);
        if (!stored) return;
        try {
            const uri = vscode.Uri.parse(stored);
            const bytes = await vscode.workspace.fs.readFile(uri);
            webviewPanel.webview.postMessage({
                type: "ssl-key-log-picked",
                keyLogPath: uri.fsPath,
                keyLogContent: new TextDecoder("utf-8").decode(bytes),
            });
        } catch {
            this.context.workspaceState.update(key, undefined);
        }
    }

    /**
     * Re-reads a script file from disk and sends the content to the webview.
     */
    private async readScriptFile(
        scriptPath: string,
        webviewPanel: vscode.WebviewPanel,
    ): Promise<void> {
        try {
            const uri = vscode.Uri.file(scriptPath);
            const bytes = await vscode.workspace.fs.readFile(uri);
            const content = new TextDecoder("utf-8").decode(bytes);
            webviewPanel.webview.postMessage({
                type: "script-file-content",
                scriptPath,
                scriptContent: content,
            });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to read script file: ${err}`);
        }
    }

    /**
     * Reads a binary a running script asked for with `open(path)` and posts it
     * back to the webview, which parses it there.
     *
     * The webview cannot touch the file system, so this is the only way its
     * scripts reach a second file — deliberately: the script stays sandboxed and
     * receives parsed bytes, never a file handle. Failures are posted rather than
     * only shown as notifications, because the script's own `try/catch` is
     * supposed to see them, exactly as it does in the IntelliJ plugin and the CLI.
     */
    private async readBinaryFile(
        requestId: string,
        binaryPath: string,
        webviewPanel: vscode.WebviewPanel,
    ): Promise<void> {
        // Exactly one reply per request, on every path. The webview keeps the
        // script suspended until one arrives, so a request answered with nothing
        // is a script that never finishes.
        try {
            const uri = vscode.Uri.file(binaryPath);
            const stat = await vscode.workspace.fs.stat(uri);
            // A device or directory has no meaningful size and may never end (/dev/zero).
            if ((stat.type & vscode.FileType.File) === 0) {
                void webviewPanel.webview.postMessage(binaryFileReadFailure(
                    requestId,
                    binaryPath,
                    "not a regular file",
                ));
                return;
            }
            const statedOversize = scriptOpenOversizeReason(stat.size);
            if (statedOversize) {
                void webviewPanel.webview.postMessage(binaryFileReadFailure(
                    requestId,
                    binaryPath,
                    statedOversize,
                ));
                return;
            }
            const bytes = await vscode.workspace.fs.readFile(uri);
            // The bytes are what the cap has to hold: the file can grow between
            // the stat above and this read, and posting the payload is what costs
            // the memory.
            const readOversize = scriptOpenOversizeReason(bytes.byteLength);
            if (readOversize) {
                void webviewPanel.webview.postMessage(binaryFileReadFailure(
                    requestId,
                    binaryPath,
                    readOversize,
                ));
                return;
            }
            void webviewPanel.webview.postMessage(binaryFileContent(requestId, binaryPath, bytes));
        } catch (err) {
            void webviewPanel.webview.postMessage(binaryFileReadFailure(
                requestId,
                binaryPath,
                fileReadFailureReason(err),
            ));
        }
    }

    /**
     * Persists a newly added script tab path in workspace state.
     */
    private addScriptTabToState(document: WasmDocument, scriptPath: string): void {
        const key = `hexana.scriptTabs.${document.uri.toString()}`;
        const existing: string[] = this.context.workspaceState.get(key, []);
        if (!existing.includes(scriptPath)) {
            this.context.workspaceState.update(key, [...existing, scriptPath]);
        }
    }

    /**
     * Removes a script tab path from workspace state.
     */
    private removeScriptTabFromState(document: WasmDocument, scriptPath: string): void {
        const key = `hexana.scriptTabs.${document.uri.toString()}`;
        const existing: string[] = this.context.workspaceState.get(key, []);
        this.context.workspaceState.update(key, existing.filter(p => p !== scriptPath));
    }

    /**
     * Sends persisted script tab paths to the webview so it can restore them.
     */
    private restoreScriptTabs(
        document: WasmDocument,
        webviewPanel: vscode.WebviewPanel,
    ): void {
        const key = `hexana.scriptTabs.${document.uri.toString()}`;
        const paths: string[] = this.context.workspaceState.get(key, []);
        if (paths.length > 0) {
            webviewPanel.webview.postMessage({
                type: "restore-script-tabs",
                scriptPaths: paths,
            });
        }
    }
}

/** Workspace-state key holding the SSL key log paired with a capture. */
function sslKeyLogStateKey(document: WasmDocument): string {
    return `hexana.sslKeyLog.${document.uri.toString()}`;
}

/**
 * Lightweight document model that holds the raw bytes of a `.wasm` file
 * or an in-memory nested module.
 */
class WasmDocument implements vscode.CustomDocument {
    /** Optional display name override (used for nested modules). */
    public readonly displayName?: string;

    constructor(
        public readonly uri: vscode.Uri,
        public readonly data: Uint8Array,
        displayName?: string,
    ) {
        this.displayName = displayName;
    }

    dispose(): void {
        // Clean up in-memory entry if present.
        inMemoryModules.delete(this.uri.toString());
    }
}
