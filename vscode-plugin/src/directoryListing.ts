/**
 * The extension-host half of `list-directory`: the webview has no file system, so when it needs to
 * know what sits next to the open file — a fingerprinted PDB or symbol map, or the files of a Blazor
 * WebAssembly app's `_framework` directory — it asks for the listing over the message bus.
 *
 * Optionally hashes some of the files (`hashNames`), so the webview can check a Blazor boot manifest's
 * integrity values without the bytes ever crossing the bus; Node's native SHA-256 makes that cheap.
 */
import {createHash} from "crypto";

/** Bit flags of `vscode.FileType`, repeated so this module stays testable without the vscode API. */
const FILE_TYPE_FILE = 1;
const FILE_TYPE_DIRECTORY = 2;

/** Cap on the entries listed: a `_framework` directory holds a few hundred. */
export const MAX_LISTED_ENTRIES = 5000;

/** Cap on a file hashed for the webview; `dotnet.native.wasm` is ~3 MB. */
export const MAX_HASHED_FILE_BYTES = 64 * 1024 * 1024;

/** The slice of `vscode.workspace.fs` this needs, over plain paths. */
export interface DirectoryFileSystem {
    readDirectory(dir: string): Promise<[string, number][]>;
    stat(path: string): Promise<{ type: number; size: number }>;
    readFile(path: string): Promise<Uint8Array>;
}

export interface DirectoryListingEntry {
    readonly name: string;
    readonly isFile: boolean;
    readonly isDirectory: boolean;
    /** Size in bytes, for files only. */
    readonly size?: number;
    /** Base64 SHA-256 of the file, for the names the request asked to hash. */
    readonly sha256?: string;
}

export interface DirectoryListingMessage {
    readonly type: "directory-listing";
    readonly requestId: string;
    readonly path: string;
    readonly entries?: DirectoryListingEntry[];
    readonly error?: string;
}

function joinPath(dir: string, name: string): string {
    if (dir.endsWith("/") || dir.endsWith("\\")) return dir + name;
    return dir + (dir.includes("\\") && !dir.includes("/") ? "\\" : "/") + name;
}

/**
 * Lists [dir]: every entry's name and kind, the size of each file, and the SHA-256 of each file named
 * in [hashNames] that is no larger than [MAX_HASHED_FILE_BYTES]. A file that cannot be read keeps its
 * entry, without the size or hash.
 */
export async function listDirectory(
    fs: DirectoryFileSystem,
    dir: string,
    hashNames: readonly string[] = [],
): Promise<DirectoryListingEntry[]> {
    const listed = (await fs.readDirectory(dir)).slice(0, MAX_LISTED_ENTRIES);
    const toHash = new Set(hashNames);
    return Promise.all(listed.map(async ([name, listedType]): Promise<DirectoryListingEntry> => {
        const path = joinPath(dir, name);
        let type = listedType;
        let size: number | undefined;
        try {
            // Follows links, which readDirectory reports as such: a linked file is a file.
            const stat = await fs.stat(path);
            type = stat.type;
            if ((type & FILE_TYPE_FILE) !== 0) size = stat.size;
        } catch {
            // Unreadable: listed with what readDirectory said.
        }
        const isFile = (type & FILE_TYPE_FILE) !== 0;
        let sha256: string | undefined;
        if (isFile && toHash.has(name) && size !== undefined && size <= MAX_HASHED_FILE_BYTES) {
            try {
                sha256 = createHash("sha256").update(await fs.readFile(path)).digest("base64");
            } catch {
                // Left unhashed; the webview reports it as not checked.
            }
        }
        return {name, isFile, isDirectory: (type & FILE_TYPE_DIRECTORY) !== 0, size, sha256};
    }));
}

export function directoryListing(requestId: string, path: string, entries: DirectoryListingEntry[]): DirectoryListingMessage {
    return {type: "directory-listing", requestId, path, entries};
}

export function directoryListingFailure(requestId: string, path: string, reason: string): DirectoryListingMessage {
    return {type: "directory-listing", requestId, path, error: reason || "The directory could not be listed."};
}
