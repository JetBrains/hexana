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
