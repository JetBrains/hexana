import * as fs from "fs";
import * as vscode from "vscode";

/**
 * The most bytes of one file the editor can hold: `MAX_BINARY_WINDOW_BYTES` in the Kotlin
 * `binaryProvider` module, which addresses bytes with 32-bit offsets. Kept equal to it.
 */
export const MAX_LOADABLE_BYTES = 2_146_435_072;

/** Default for `hexana.maxLoadedFileSizeMB`. */
export const DEFAULT_MAX_LOADED_MB = 512;

/**
 * How much of one file the editor loads, from `hexana.maxLoadedFileSizeMB`. A larger file opens
 * as its first that many bytes. Lower than the Kotlin limit by default because a webview gets
 * its own copy of whatever the extension host read.
 */
export function maxLoadedBytes(): number {
    const mb = vscode.workspace.getConfiguration("hexana").get<number>("maxLoadedFileSizeMB", DEFAULT_MAX_LOADED_MB);
    return clampLoadedBytes(mb);
}

/** `mb` megabytes, as a byte count the editor can load: at least 1 MiB, at most [MAX_LOADABLE_BYTES]. */
export function clampLoadedBytes(mb: number): number {
    const requested = Number.isFinite(mb) ? Math.floor(mb) : DEFAULT_MAX_LOADED_MB;
    return Math.min(Math.max(requested, 1) * 1024 * 1024, MAX_LOADABLE_BYTES);
}

/** What [readHead] loaded: [data] is the whole file exactly when its length is [fileSize]. */
export interface LoadedHead {
    data: Uint8Array;
    fileSize: number;
}

/** A file larger than the editor can load, on a file system that cannot read just part of it. */
export class FileTooLargeError extends Error {
    constructor(readonly fileSize: number, readonly maxBytes: number) {
        super(
            `This file is ${formatSize(fileSize)}; Hexana can open at most ${formatSize(maxBytes)} of a file ` +
            `that is not on the local disk.`,
        );
        this.name = "FileTooLargeError";
    }
}

/**
 * Reads at most [maxBytes] from the start of [uri]. A local file is read through a file handle,
 * so a file of any size costs only what is loaded; `workspace.fs` has no ranged read, so another
 * scheme is read whole when it fits and refused with [FileTooLargeError] when it does not.
 */
export async function readHead(uri: vscode.Uri, maxBytes: number): Promise<LoadedHead> {
    if (uri.scheme === "file") {
        const handle = await fs.promises.open(uri.fsPath, "r");
        try {
            const { size } = await handle.stat();
            const length = Math.min(size, maxBytes);
            const data = Buffer.allocUnsafe(length);
            let offset = 0;
            while (offset < length) {
                const { bytesRead } = await handle.read(data, offset, length - offset, offset);
                if (bytesRead === 0) break;
                offset += bytesRead;
            }
            // A file that shrank since the stat reads short; it is then whole.
            return { data: data.subarray(0, offset), fileSize: offset < length ? offset : size };
        } finally {
            await handle.close();
        }
    }
    const stat = await vscode.workspace.fs.stat(uri);
    if (stat.size > maxBytes) throw new FileTooLargeError(stat.size, maxBytes);
    const data = await vscode.workspace.fs.readFile(uri);
    return { data, fileSize: data.byteLength };
}

/**
 * [length] bytes of the local file [uri] from [offset] (fewer at its end). For the webview's hex
 * view, which shows a file larger than it holds by asking for the bytes it scrolls to.
 */
export async function readRange(uri: vscode.Uri, offset: number, length: number): Promise<Uint8Array> {
    if (uri.scheme !== "file") throw new Error("Only local files can be read in ranges.");
    if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(length) || length < 0) {
        throw new Error(`Invalid range ${offset}+${length}.`);
    }
    const handle = await fs.promises.open(uri.fsPath, "r");
    try {
        const { size } = await handle.stat();
        const count = Math.max(0, Math.min(length, MAX_RANGE_BYTES, size - offset));
        const data = Buffer.allocUnsafe(count);
        let done = 0;
        while (done < count) {
            const { bytesRead } = await handle.read(data, done, count - done, offset + done);
            if (bytesRead === 0) break;
            done += bytesRead;
        }
        return data.subarray(0, done);
    } finally {
        await handle.close();
    }
}

/** Most bytes one [readRange] returns; the webview asks for 64 KiB pages. */
export const MAX_RANGE_BYTES = 16 * 1024 * 1024;

/** Bytes read per step of [searchFile]. */
const SEARCH_CHUNK = 8 * 1024 * 1024;

/**
 * Offset of the first occurrence of [pattern] in the local file [uri] at or after [from] (at or
 * before it when [reverse]), or -1. Streams the file in chunks, overlapping by the pattern's length,
 * so it works on files of any size without holding them.
 */
export async function searchFile(uri: vscode.Uri, pattern: Uint8Array, from: number, reverse: boolean): Promise<number> {
    if (uri.scheme !== "file") throw new Error("Only local files can be searched.");
    if (pattern.length === 0) return -1;
    const handle = await fs.promises.open(uri.fsPath, "r");
    try {
        const { size } = await handle.stat();
        const lastStart = size - pattern.length;
        if (lastStart < 0) return -1;
        const buffer = Buffer.allocUnsafe(SEARCH_CHUNK + pattern.length - 1);
        const needle = Buffer.from(pattern.buffer, pattern.byteOffset, pattern.byteLength);
        if (!reverse) {
            let start = Math.max(0, from);
            while (start <= lastStart) {
                const starts = Math.min(SEARCH_CHUNK, lastStart - start + 1);
                const { bytesRead } = await handle.read(buffer, 0, starts + pattern.length - 1, start);
                const hit = buffer.subarray(0, bytesRead).indexOf(needle);
                if (hit >= 0 && hit < starts) return start + hit;
                start += starts;
            }
        } else {
            let end = Math.min(from, lastStart); // last start examined, inclusive
            while (end >= 0) {
                const starts = Math.min(SEARCH_CHUNK, end + 1);
                const chunkStart = end - starts + 1;
                const { bytesRead } = await handle.read(buffer, 0, starts + pattern.length - 1, chunkStart);
                const hit = buffer.subarray(0, bytesRead).lastIndexOf(needle, starts - 1);
                if (hit >= 0) return chunkStart + hit;
                end = chunkStart - 1;
            }
        }
        return -1;
    } finally {
        await handle.close();
    }
}

/** The first [length] bytes of [uri] (fewer for a shorter file), for magic sniffing. */
export async function readPrefix(uri: vscode.Uri, length: number): Promise<Uint8Array> {
    if (uri.scheme === "file") return (await readHead(uri, length)).data;
    return (await vscode.workspace.fs.readFile(uri)).subarray(0, length);
}

/** Whether a document holding [loaded] bytes of a [fileSize]-byte file shows only part of it. */
export function isPartial(loaded: number, fileSize: number): boolean {
    return fileSize > loaded;
}

/** `formatSizeLong` from `plugins-shared`, for messages built on the host side. */
export function formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}
