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

/** Cap on a file hashed for the webview; `dotnet.native.wasm` is ~3 MB, ~14 MB compiled ahead of time. */
export const MAX_HASHED_FILE_BYTES = 64 * 1024 * 1024;

/**
 * Cap on all bytes hashed for one request. The names to hash come from a boot manifest, which is input:
 * a crafted one could name every large file in the directory. A real app is ~10 MB (~20 MB with AOT).
 */
export const MAX_HASHED_TOTAL_BYTES = 256 * 1024 * 1024;

/** File system operations in flight at once: enough to overlap I/O, few enough to bound memory. */
export const MAX_CONCURRENT_OPERATIONS = 8;

/** The caps [listDirectory] applies; the defaults are the production values, tests pass smaller ones. */
export interface DirectoryListingLimits {
    readonly maxHashedFileBytes: number;
    readonly maxHashedTotalBytes: number;
    readonly maxConcurrentOperations: number;
}

export const DEFAULT_LISTING_LIMITS: DirectoryListingLimits = {
    maxHashedFileBytes: MAX_HASHED_FILE_BYTES,
    maxHashedTotalBytes: MAX_HASHED_TOTAL_BYTES,
    maxConcurrentOperations: MAX_CONCURRENT_OPERATIONS,
};

/** [work] for every item, at most [limit] at a time; results in item order. */
async function mapBounded<T, R>(items: readonly T[], limit: number, work: (item: T) => Promise<R>): Promise<R[]> {
    const results = new Array<R>(items.length);
    let next = 0;
    const workers = Array.from({length: Math.min(limit, items.length)}, async () => {
        while (next < items.length) {
            const index = next++;
            results[index] = await work(items[index]);
        }
    });
    await Promise.all(workers);
    return results;
}

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
 * in [hashNames] that is no larger than [MAX_HASHED_FILE_BYTES], until [MAX_HASHED_TOTAL_BYTES] have been
 * hashed. At most [MAX_CONCURRENT_OPERATIONS] stats or reads run at once. A file that cannot be read keeps
 * its entry, without the size or hash; so does one past the budget, which the webview shows as not checked.
 */
export async function listDirectory(
    fs: DirectoryFileSystem,
    dir: string,
    hashNames: readonly string[] = [],
    limits: DirectoryListingLimits = DEFAULT_LISTING_LIMITS,
): Promise<DirectoryListingEntry[]> {
    const listed = (await fs.readDirectory(dir)).slice(0, MAX_LISTED_ENTRIES);
    const toHash = new Set(hashNames);
    const entries = await mapBounded(listed, limits.maxConcurrentOperations, async ([name, listedType]) => {
        let type = listedType;
        let size: number | undefined;
        try {
            // Follows links, which readDirectory reports as such: a linked file is a file.
            const stat = await fs.stat(joinPath(dir, name));
            type = stat.type;
            if ((type & FILE_TYPE_FILE) !== 0) size = stat.size;
        } catch {
            // Unreadable: listed with what readDirectory said.
        }
        return {name, isFile: (type & FILE_TYPE_FILE) !== 0, isDirectory: (type & FILE_TYPE_DIRECTORY) !== 0, size};
    });

    // The budget is reserved by stat size before any read starts, so concurrent reads cannot overrun it.
    let budget = limits.maxHashedTotalBytes;
    const hashable = entries.filter((e) => {
        if (!e.isFile || !toHash.has(e.name) || e.size === undefined || e.size > limits.maxHashedFileBytes || e.size > budget) return false;
        budget -= e.size;
        return true;
    });
    const hashes = new Map<string, string>();
    await mapBounded(hashable, limits.maxConcurrentOperations, async (entry) => {
        try {
            const bytes = await fs.readFile(joinPath(dir, entry.name));
            // The file can grow between the stat and the read; only what was reserved is hashed.
            if (bytes.byteLength !== entry.size) return;
            hashes.set(entry.name, createHash("sha256").update(bytes).digest("base64"));
        } catch {
            // Left unhashed; the webview reports it as not checked.
        }
    });
    return entries.map((e) => ({...e, sha256: hashes.get(e.name)}));
}

export function directoryListing(requestId: string, path: string, entries: DirectoryListingEntry[]): DirectoryListingMessage {
    return {type: "directory-listing", requestId, path, entries};
}

export function directoryListingFailure(requestId: string, path: string, reason: string): DirectoryListingMessage {
    return {type: "directory-listing", requestId, path, error: reason || "The directory could not be listed."};
}
