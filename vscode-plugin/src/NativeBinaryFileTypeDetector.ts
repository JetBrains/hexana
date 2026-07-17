import * as vscode from "vscode";

/**
 * Magic-byte detector that identifies extensionless (or ambiguously-named) native
 * binary files and re-opens them with the Hexana custom editor.
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

/** Extensions already handled by the customEditors filenamePattern in package.json. */
const KNOWN_EXTENSIONS = new Set([
    ".wasm", ".bin", ".elf", ".exe", ".so", ".o", ".a", ".dll", ".dylib",
    ".jar", ".zip", ".war", ".apk", ".class", ".dex", ".desc", ".protoset",
]);

/**
 * Detects whether the given byte prefix matches a known native binary format.
 * Returns true for ELF, Mach-O (thin and fat/universal), PE, static-library
 * archive, and COFF object files.
 */
function isNativeBinary(bytes: Uint8Array): boolean {
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
 * name is used as the pattern.
 */
function persistEditorAssociation(uri: vscode.Uri): void {
    const ext = getExtension(uri);
    const pattern = ext !== "" ? `*${ext}` : getFileName(uri);
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
