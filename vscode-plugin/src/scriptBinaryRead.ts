/**
 * The extension-host half of `open(path)` in a script tab.
 *
 * A script runs inside the webview, which has no file system. When it calls
 * `open(path)` the webview asks for the bytes over the message bus and parses
 * them itself, so the script gets parsed data and never a file handle. This
 * module owns the shape of that reply: it is the one place both the byte
 * normalization and the failure wording live, because the webview decodes the
 * payload without types and a script author reads the failure text.
 */

/**
 * Cap on a file a script may `open`. Past this the reply is a failure the
 * script can catch, not a silently truncated read.
 */
export const MAX_SCRIPT_OPEN_BYTES = 100 * 1024 * 1024;

/** Message of [err] as a user would read it. */
export function errorMessageText(err: unknown): string {
    return err instanceof Error ? err.message : String(err);
}

/**
 * Bytes in the shape the webview can decode.
 *
 * Every byte payload posted to the webview must go through this. The webview
 * decodes a payload as a plain array-like — it reads `.length` and indexes it —
 * and a Node `Buffer` does not arrive as one, so a `Buffer` posted unchanged
 * reaches the webview without a numeric `length` and its decode throws.
 * `vscode.workspace.fs.readFile` returns a `Buffer` on the desktop build, which
 * makes this conversion mandatory rather than defensive.
 */
export function toWebviewBytes(bytes: Uint8Array): Uint8Array {
    return bytes instanceof Buffer
        ? new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)
        : bytes;
}

/**
 * Why a file read failed, in the words the JVM script runtime uses for the same
 * cause, so one script reports the same thing wherever it runs. VS Code's own
 * text ("Unable to read file 'x' (Error: ENOENT: ...)") carries the path twice
 * and the errno, neither of which helps a script author.
 */
export function fileReadFailureReason(err: unknown): string {
    const code = (err as { code?: unknown } | undefined)?.code;
    switch (code) {
        case "FileNotFound":
            return "File not found";
        case "FileIsADirectory":
            return "File not found";
        case "NoPermissions":
            return "File is not readable";
        default:
            return errorMessageText(err);
    }
}

/** A reply to one `read-binary-file` request, ready to post to the webview. */
export interface BinaryFileContentMessage {
    readonly type: "binary-file-content";
    readonly requestId: string;
    readonly path: string;
    readonly data?: Uint8Array;
    readonly error?: string;
}

/** The reply that hands [bytes] to the script that asked for [binaryPath]. */
export function binaryFileContent(
    requestId: string,
    binaryPath: string,
    bytes: Uint8Array,
): BinaryFileContentMessage {
    return {
        type: "binary-file-content",
        requestId,
        path: binaryPath,
        data: toWebviewBytes(bytes),
    };
}

/**
 * The reply that fails the `open(binaryPath)` call with [reason], phrased the
 * way the JVM runtime phrases it, because the script's own `catch` sees this
 * string and a script is meant to read the same in every host.
 */
export function binaryFileReadFailure(
    requestId: string,
    binaryPath: string,
    reason: string,
): BinaryFileContentMessage {
    return {
        type: "binary-file-content",
        requestId,
        path: binaryPath,
        error: `open("${binaryPath}") — ${reason}`,
    };
}
