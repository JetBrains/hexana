import * as vscode from "vscode";

/**
 * Magic-byte detector that identifies extensionless (or ambiguously-named) binary
 * files and re-opens them with the Hexana custom editor.
 *
 * This is the VS Code equivalent of the IntelliJ `NativeBinaryFileTypeDetector`.
 * VS Code does not have a built-in content-based file-type detection API for custom
 * editors, so we use two complementary strategies:
 *
 * 1. **Proactive tab interception** — `tabGroups.onDidChangeTabs` fires when a new
 *    tab is added, even before the user interacts with the "binary file not displayed"
 *    warning. We read the file's magic bytes and, if it matches a known binary format,
 *    immediately close the text tab and re-open with the Hexana custom editor.
 *
 * 2. **Persistent editor associations** — after the first successful detection, the
 *    file's glob pattern (based on its name) is persisted in the user's global
 *    `workbench.editorAssociations` setting so that subsequent opens go directly to
 *    Hexana without any flash or re-open.
 */

const HEXANA_VIEW_TYPE = "hexana.wasmEditor";

/** Minimum prefix length needed for all magic checks (matches IntelliJ's 0x100). */
const DESIRED_PREFIX_LENGTH = 0x100;

/** COFF machine types that mark a real COFF object header. */
const COFF_MACHINE_TYPES = new Set([0x014C, 0x01C0, 0x01C4, 0x0200, 0x8664, 0xAA64]);

/**
 * Extensions the `default`-priority customEditors selector in package.json already
 * claims, so VS Code opens them in Hexana on its own and this detector has nothing to
 * do. The second, `option`-priority contribution matches `*`, but only to offer Hexana
 * under "Reopen Editor With…" — it never opens anything by itself, so it does not make
 * this list redundant.
 */
const KNOWN_EXTENSIONS = new Set([
    ".wasm", ".bin", ".elf", ".exe", ".so", ".o", ".a", ".dll", ".dylib",
    ".jar", ".zip", ".war", ".apk", ".class", ".dex", ".arsc", ".resources", ".desc", ".protoset",
    ".pcap", ".cap", ".pcapng", ".parquet", ".arrow", ".arrows", ".pb",
    ".tar", ".tgz", ".gz",
]);

/**
 * Detects whether the given byte prefix matches a binary format Hexana can open.
 * Returns true for ELF, Mach-O (thin and fat/universal), PE, static-library
 * archive, COFF object files, WebAssembly, .NET resource sets, Windows and Portable PDBs, pcap/pcapng,
 * Parquet and Arrow IPC files.
 */
export function isNativeBinary(bytes: Uint8Array): boolean {
    const len = bytes.length;
    if (len < 4) return false;

    const b0 = bytes[0];
    const b1 = bytes[1];
    const b2 = bytes[2];
    const b3 = bytes[3];

    // Static-library archive: "!<arch>\n" (21 3C 61 72 63 68 3E 0A)
    if (len >= 8 &&
        b0 === 0x21 && b1 === 0x3C && b2 === 0x61 && b3 === 0x72 &&
        bytes[4] === 0x63 && bytes[5] === 0x68 && bytes[6] === 0x3E && bytes[7] === 0x0A) {
        return true;
    }

    // ELF: 7F 45 4C 46
    if (b0 === 0x7F && b1 === 0x45 && b2 === 0x4C && b3 === 0x46) {
        return true;
    }

    // Mach-O thin: FE ED FA CE/CF (big-endian), CE/CF FA ED FE (little-endian)
    if ((b0 === 0xFE && b1 === 0xED && b2 === 0xFA && (b3 === 0xCE || b3 === 0xCF)) ||
        (b3 === 0xFE && b2 === 0xED && b1 === 0xFA && (b0 === 0xCE || b0 === 0xCF))) {
        return true;
    }

    // Mach-O fat/universal: CA FE BA BE/BF or reversed.
    // CA FE BA BE is also the Java class file magic — disambiguate using the
    // heuristic from ClassFileBinaryProvider.isDefinitelyClass: bytes 4..7 as
    // a big-endian u32 are a small arch count (≤ ~18) for Mach-O fat binaries,
    // but minor_version|major_version (≥ 43) for Java class files, yielding a
    // value well above 30.
    if ((b0 === 0xCA && b1 === 0xFE && b2 === 0xBA && (b3 === 0xBE || b3 === 0xBF)) ||
        (b3 === 0xCA && b2 === 0xFE && b1 === 0xBA && (b0 === 0xBE || b0 === 0xBF))) {
        if (len >= 8) {
            const word4 = ((bytes[4] & 0xFF) << 24) | ((bytes[5] & 0xFF) << 16) |
                ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
            // word4 > 30 means Java class file — not a native binary.
            if (word4 > 30) return false;
        }
        return true;
    }

    // PE: 4D 5A ("MZ"), then check e_lfanew at offset 0x3C and PE signature
    if (b0 === 0x4D && b1 === 0x5A && len >= 0x40) {
        const eLfanew = readU32LE(bytes, 0x3C);
        if (eLfanew >= 0x40 && eLfanew < len - 3) {
            if (bytes[eLfanew] === 0x50 && bytes[eLfanew + 1] === 0x45 &&
                bytes[eLfanew + 2] === 0x00 && bytes[eLfanew + 3] === 0x00) {
                return true;
            }
        }
    }

    // COFF object: structural check from the 20-byte header
    if (len >= 20) {
        const machine = b0 | (b1 << 8);
        const sectionCount = b2 | (b3 << 8);
        const optionalHeaderSize = readU16LE(bytes, 16);
        const symbolTableOffset = readU32LE(bytes, 8);
        if (COFF_MACHINE_TYPES.has(machine) &&
            sectionCount >= 1 && sectionCount <= 96 &&
            optionalHeaderSize === 0 &&
            (symbolTableOffset === 0 || symbolTableOffset >= 20)) {
            return true;
        }
    }

    // WebAssembly: 00 61 73 6D ("\0asm")
    if (b0 === 0x00 && b1 === 0x61 && b2 === 0x73 && b3 === 0x6D) {
        return true;
    }

    // .NET resource set (.resources): ResourceManager magic 0xBEEFCACE, little-endian
    if (b0 === 0xCE && b1 === 0xCA && b2 === 0xEF && b3 === 0xBE) {
        return true;
    }

    // PDB debug symbols: matched by content only, because `.pdb` is also the Protein Data Bank
    // text format. Windows PDB: "Microsoft C/C++ MSF 7.00\r\n\x1aDS\0\0\0".
    if (startsWithAscii(bytes, MSF7_MAGIC)) {
        return true;
    }
    // Portable PDB: a metadata root ("BSJB") whose version string is "PDB v1.0".
    if (b0 === 0x42 && b1 === 0x53 && b2 === 0x4A && b3 === 0x42 && len >= 24 &&
        startsWithAscii(bytes.subarray(16), "PDB v")) {
        return true;
    }

    // PCAP: A1 B2 C3 D4 (LE) or D4 C3 B2 A1 (BE), nanosecond variants A1 B2 3C 4D / 4D 3C B2 A1
    if ((b0 === 0xA1 && b1 === 0xB2 && b2 === 0xC3 && b3 === 0xD4) ||
        (b0 === 0xD4 && b1 === 0xC3 && b2 === 0xB2 && b3 === 0xA1) ||
        (b0 === 0xA1 && b1 === 0xB2 && b2 === 0x3C && b3 === 0x4D) ||
        (b0 === 0x4D && b1 === 0x3C && b2 === 0xB2 && b3 === 0xA1)) {
        return true;
    }

    // PCAP-NG: Section Header Block type 0A 0D 0D 0A followed by BOM at offset 8
    if (b0 === 0x0A && b1 === 0x0D && b2 === 0x0D && b3 === 0x0A && len >= 12) {
        const bom = readU32LE(bytes, 8);
        if (bom === 0x1A2B3C4D || bom === 0x4D3C2B1A) {
            return true;
        }
    }

    // Parquet: "PAR1" (50 41 52 31). The magic also repeats at the tail, but this
    // detector only ever receives a head prefix, so the head check stands alone —
    // same call the IntelliJ `ColumnarBinaryFileTypeDetector` makes.
    if (b0 === 0x50 && b1 === 0x41 && b2 === 0x52 && b3 === 0x31) {
        return true;
    }

    // Arrow IPC file: "ARROW1" (41 52 52 4F 57 31). The Arrow *stream* format is
    // deliberately not detected: its only marker is a 0xFFFFFFFF continuation word at
    // offset 0, which any all-0xFF prefix would match. Streams reach the editor
    // through the .arrow / .arrows extension instead.
    if (len >= 6 &&
        b0 === 0x41 && b1 === 0x52 && b2 === 0x52 && b3 === 0x4F &&
        bytes[4] === 0x57 && bytes[5] === 0x31) {
        return true;
    }

    return false;
}

function readU16LE(bytes: Uint8Array, offset: number): number {
    return bytes[offset] | (bytes[offset + 1] << 8);
}

function readU32LE(bytes: Uint8Array, offset: number): number {
    return (bytes[offset] | (bytes[offset + 1] << 8) |
        (bytes[offset + 2] << 16) | (bytes[offset + 3] << 24)) >>> 0;
}

/**
 * Returns the lowercase file extension including the dot, or an empty string.
 */
function getExtension(uri: vscode.Uri): string {
    const fsPath = uri.fsPath;
    const dot = fsPath.lastIndexOf(".");
    const sep = Math.max(fsPath.lastIndexOf("/"), fsPath.lastIndexOf("\\"));
    if (dot > sep && dot < fsPath.length - 1) {
        return fsPath.substring(dot).toLowerCase();
    }
    return "";
}

/**
 * Returns the file name (basename) from a URI.
 */
function getFileName(uri: vscode.Uri): string {
    const fsPath = uri.fsPath;
    const sep = Math.max(fsPath.lastIndexOf("/"), fsPath.lastIndexOf("\\"));
    return sep >= 0 ? fsPath.substring(sep + 1) : fsPath;
}

/**
 * Persists a `workbench.editorAssociations` entry for the given file so that
 * subsequent opens go directly to the Hexana custom editor without the
 * "binary file not displayed" warning.
 *
 * For files with an extension, the glob pattern is `*.<ext>` (so all files
 * sharing that extension benefit). For extensionless files, the exact file
 * name is used as the pattern. Extensions shared with text formats are never
 * persisted: one binary `.pdb` must not send every Protein Data Bank file to
 * Hexana, so those keep being sniffed on each open.
 */
function persistEditorAssociation(uri: vscode.Uri): void {
    const pattern = editorAssociationPattern(uri);
    if (!pattern) return;

    const config = vscode.workspace.getConfiguration("workbench");
    const inspected = config.inspect<Record<string, string>>("editorAssociations");
    const current = inspected?.globalValue ?? {};

    if (current[pattern] !== undefined) return;

    config.update(
        "editorAssociations",
        { ...current, [pattern]: HEXANA_VIEW_TYPE },
        vscode.ConfigurationTarget.Global,
    );
}

/**
 * Registers listeners that detect extensionless native binary files and re-open
 * them with the Hexana custom editor.
 *
 * Uses `tabGroups.onDidChangeTabs` for proactive interception (fires before the
 * user interacts with the binary warning) and persists editor associations so
 * subsequent opens are instant.
 *
 * Files whose extensions are already covered by the `customEditors` selector in
 * `package.json` are skipped — they are handled by the normal editor association.
 */
export function registerNativeBinaryDetector(context: vscode.ExtensionContext): void {
    // Track URIs we've already attempted to re-open to avoid infinite loops.
    const reopenedUris = new Set<string>();

    // Use onDidChangeTabs to intercept new text tabs for binary files before
    // the user sees the "binary file not displayed" warning.
    context.subscriptions.push(
        vscode.window.tabGroups.onDidChangeTabs(async (event) => {
            for (const tab of event.opened) {
                // Only intercept tabs opened with the default text editor.
                if (!(tab.input instanceof vscode.TabInputText)) continue;

                const uri = tab.input.uri;

                // Only inspect file-scheme URIs.
                if (uri.scheme !== "file") continue;

                // Skip files with extensions already handled by the customEditors pattern.
                const ext = getExtension(uri);
                if (ext !== "" && KNOWN_EXTENSIONS.has(ext)) continue;

                // Guard: skip .class files for the CA FE BA BE ambiguity.
                if (ext === ".class") continue;

                // Avoid re-triggering on the same file.
                const key = uri.toString();
                if (reopenedUris.has(key)) continue;

                // Read a small prefix to check magic bytes.
                try {
                    const fileBytes = await vscode.workspace.fs.readFile(uri);
                    const prefixLen = Math.min(fileBytes.length, DESIRED_PREFIX_LENGTH);
                    if (prefixLen < 4) continue;
                    const prefix = fileBytes.subarray(0, prefixLen);

                    if (isNativeBinary(prefix)) {
                        reopenedUris.add(key);

                        // Close the text/binary-warning tab and re-open with Hexana.
                        await vscode.window.tabGroups.close(tab);
                        await vscode.commands.executeCommand(
                            "vscode.openWith", uri, HEXANA_VIEW_TYPE,
                        );

                        // Persist the association so future opens skip the warning entirely.
                        persistEditorAssociation(uri);
                    }
                } catch {
                    // File read failed — not a local file or permission issue; ignore.
                }
            }
        }),
    );
}

/** The `workbench.editorAssociations` pattern to persist for [uri], or null to persist none. */
export function editorAssociationPattern(uri: vscode.Uri): string | null {
    const ext = getExtension(uri);
    if (CONTENT_TYPED_EXTENSIONS.has(ext)) return null;
    return (ext !== "" ? `*${ext}` : getFileName(uri)) || null;
}

/** Extensions that text formats share, so only the content of each file decides (`.pdb`: PDB or Protein Data Bank). */
export const CONTENT_TYPED_EXTENSIONS = new Set([".pdb"]);

const MSF7_MAGIC = "Microsoft C/C++ MSF 7.00\r\n\x1aDS\0\0\0";

function startsWithAscii(bytes: Uint8Array, text: string): boolean {
    if (bytes.length < text.length) return false;
    for (let i = 0; i < text.length; i++) {
        if (bytes[i] !== text.charCodeAt(i)) return false;
    }
    return true;
}
