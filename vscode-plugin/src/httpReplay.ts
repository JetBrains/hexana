/**
 * The extension-host half of replaying an HTTP request captured in a PCAP.
 *
 * The webview builds the request - reassembly, header filtering and the `.http`
 * rendering are shared Kotlin in `parsing/pcap` - but it cannot send it: the
 * webview's CSP and CORS would block the request. So it posts the edited
 * request here, and this module sends it with Node's `fetch` and posts the
 * answer back. It also writes the request as an `.http` file for the REST
 * Client extension.
 *
 * Nothing here imports `vscode`, so the unit tests can drive it directly.
 */

import * as fs from "fs/promises";
import * as os from "os";
import * as path from "path";

/** The REST Client extension (`humao.rest-client`), which runs `.http` files. */
export const REST_CLIENT_EXTENSION_ID = "humao.rest-client";

/** What the webview posts as `http-replay-send`. */
export interface HttpReplaySendMessage {
    requestId: string;
    method: string;
    url: string;
    /** `[name, value]` pairs, already reduced to what a client may send. */
    headers: [string, string][];
    body: ArrayLike<number>;
}

/** What goes back as `http-replay-response`; `error` set means nothing came back. */
export interface HttpReplayResponseMessage {
    type: "http-replay-response";
    requestId: string;
    status: number;
    statusText: string;
    headers: [string, string][];
    /** At most [HTTP_REPLAY_MAX_RESPONSE_BYTES]. */
    body: Uint8Array;
    /** `true` when the server sent more than `body` holds; the rest was not read. */
    bodyTruncated: boolean;
    elapsedMs: number;
    error: string;
}

/** How long one replay may take, connect and body included. */
export const HTTP_REPLAY_TIMEOUT_MS = 60_000;

/** How much of a response body is kept, so a huge answer cannot fill the extension host's heap. */
export const HTTP_REPLAY_MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

/**
 * Sends a replayed request and describes the answer. Never throws: a failure
 * becomes [HttpReplayResponseMessage.error], because the webview is waiting on
 * exactly one reply per request.
 *
 * Redirects are reported rather than followed, matching the IntelliJ replay.
 * `fetch` undoes content coding itself, so the body is already decompressed;
 * the `Content-Encoding` header still says what was on the wire.
 */
export async function sendHttpReplay(
    message: HttpReplaySendMessage,
    fetchImpl: typeof fetch = fetch,
): Promise<HttpReplayResponseMessage> {
    const reply: HttpReplayResponseMessage = {
        type: "http-replay-response",
        requestId: message.requestId,
        status: 0,
        statusText: "",
        headers: [],
        body: new Uint8Array(0),
        bodyTruncated: false,
        elapsedMs: 0,
        error: "",
    };
    const started = Date.now();
    try {
        const url = new URL(message.url);
        if (url.protocol !== "http:" && url.protocol !== "https:") {
            throw new Error("Only http and https URLs can be replayed");
        }
        const body = Uint8Array.from(message.body ?? []);
        const headers = new Headers();
        for (const [name, value] of message.headers ?? []) {
            headers.append(name, value);
        }
        const response = await fetchImpl(url, {
            method: message.method,
            headers,
            body: body.length > 0 ? body : undefined,
            redirect: "manual",
            signal: AbortSignal.timeout(HTTP_REPLAY_TIMEOUT_MS),
        });
        reply.status = response.status;
        reply.statusText = response.statusText;
        response.headers.forEach((value, name) => reply.headers.push([name, value]));
        const read = await readCapped(response, HTTP_REPLAY_MAX_RESPONSE_BYTES);
        reply.body = read.body;
        reply.bodyTruncated = read.truncated;
    } catch (err) {
        reply.error = describeFailure(err);
    }
    reply.elapsedMs = Date.now() - started;
    return reply;
}

/** The first [limit] bytes of [response]'s body; the rest is cancelled unread. */
async function readCapped(response: Response, limit: number): Promise<{body: Uint8Array; truncated: boolean}> {
    if (!response.body) {
        return {body: new Uint8Array(0), truncated: false};
    }
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let size = 0;
    let truncated = false;
    for (;;) {
        const {done, value} = await reader.read();
        if (done) {
            break;
        }
        if (size + value.length > limit) {
            chunks.push(value.subarray(0, limit - size));
            size = limit;
            truncated = true;
            await reader.cancel();
            break;
        }
        chunks.push(value);
        size += value.length;
    }
    const body = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) {
        body.set(chunk, offset);
        offset += chunk.length;
    }
    return {body, truncated};
}

/**
 * Why a replay failed, as a user would read it. `fetch` reports every network
 * failure as "fetch failed" and keeps the real reason (ECONNREFUSED, a
 * certificate error) in `cause`.
 */
function describeFailure(err: unknown): string {
    if (err instanceof Error) {
        if (err.name === "TimeoutError") {
            return `No answer within ${HTTP_REPLAY_TIMEOUT_MS / 1000} seconds`;
        }
        const cause = (err as { cause?: unknown }).cause;
        if (cause instanceof Error && cause.message) {
            return `${err.message}: ${cause.message}`;
        }
        return err.message;
    }
    return String(err);
}

/** What the webview posts as `open-http-request-file`. */
export interface HttpRequestFileMessage {
    /** e.g. `hexana-replay-packet-4.http` */
    fileName: string;
    /** The request in `.http` syntax, already rendered by the webview. */
    text: string;
    /** File the text reads with `< ./name`, or empty when the body is inline. */
    bodyFileName: string;
    body: ArrayLike<number>;
}

/**
 * Writes the request into a fresh directory under the system temp dir and
 * returns the `.http` file's path.
 *
 * A real file rather than an untitled document: REST Client resolves a
 * `< ./body` reference against the document's folder, which an untitled
 * document does not have, and the request should outlive the capture's tab.
 */
export async function writeHttpRequestFile(
    message: HttpRequestFileMessage,
    tempRoot: string = os.tmpdir(),
): Promise<string> {
    const dir = await fs.mkdtemp(path.join(tempRoot, "hexana-replay-"));
    const requestPath = path.join(dir, safeFileName(message.fileName, "request.http"));
    if (message.bodyFileName) {
        await fs.writeFile(
            path.join(dir, safeFileName(message.bodyFileName, "body.bin")),
            Uint8Array.from(message.body ?? []),
        );
    }
    await fs.writeFile(requestPath, message.text, "utf8");
    return requestPath;
}

/** [name] stripped to a bare file name, so a message cannot write outside its directory. */
function safeFileName(name: string, fallback: string): string {
    const base = path.basename(name ?? "");
    return base && base !== "." && base !== ".." ? base : fallback;
}
