import { createServer } from "node:http";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, normalize } from "node:path";
import { spawn } from "node:child_process";

const [packageDir, fixture] = process.argv.slice(2);
if (!packageDir || !fixture) throw new Error("usage: production-package-smoke.mjs <package-dir> <wasm-fixture>");
const policy = JSON.parse(await readFile(join(packageDir, "hosting-policy.json"), "utf8"));
const mime = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".wasm": "application/wasm", ".json": "application/json; charset=utf-8" };
const server = createServer(async (request, response) => {
    const url = new URL(request.url, "http://localhost");
    const relative = url.pathname === "/" ? "index.html" : normalize(url.pathname).replace(/^[/\\]+/, "");
    if (relative.startsWith("..")) return response.writeHead(404).end();
    try {
        const body = await readFile(join(packageDir, relative));
        const extension = relative.slice(relative.lastIndexOf("."));
        response.writeHead(200, { ...policy.headers, "Cache-Control": relative === "index.html" ? policy.cache["index.html"] : relative.startsWith("assets/") ? policy.cache["assets/*"] : policy.cache.evidence, "Content-Type": mime[extension] || "application/octet-stream" });
        response.end(body);
    } catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const port = server.address().port;
const profile = await mkdtemp(join(tmpdir(), "hexana-webapp-smoke-"));
const candidates = [process.env.CHROME_BIN, "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "google-chrome", "chromium", "chromium-browser"].filter(Boolean);
let browser;
for (const candidate of candidates) {
    browser = spawn(candidate, ["--headless=new", "--no-first-run", "--no-default-browser-check", "--remote-debugging-port=9222", `--user-data-dir=${profile}`, `http://127.0.0.1:${port}/`], { stdio: "ignore" });
    await new Promise(resolve => setTimeout(resolve, 250));
    if (!browser.exitCode) break;
}
if (!browser || browser.exitCode) throw new Error("Chrome/Chromium is required for production-package smoke");
try {
    let page;
    for (let attempt = 0; attempt < 40; attempt++) {
        try { page = (await (await fetch("http://127.0.0.1:9222/json/list")).json()).find(target => target.type === "page"); } catch {}
        if (page) break;
        await new Promise(resolve => setTimeout(resolve, 100));
    }
    if (!page) throw new Error("Chrome DevTools page was not available");
    const socket = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
    let nextId = 1;
    const pending = new Map();
    socket.onmessage = event => { const message = JSON.parse(event.data); if (pending.has(message.id)) pending.get(message.id)(message); };
    const evaluate = expression => new Promise((resolve, reject) => { const id = nextId++; pending.set(id, result => result.error ? reject(result.error) : resolve(result.result?.result?.value)); socket.send(JSON.stringify({ id, method: "Runtime.evaluate", params: { expression, awaitPromise: true, returnByValue: true } })); });
    for (let attempt = 0; attempt < 80; attempt++) {
        if (await evaluate("Boolean(document.querySelector('[data-file-drop-zone]'))")) break;
        await new Promise(resolve => setTimeout(resolve, 100));
    }
    const bytes = [...await readFile(fixture)];
    const dropped = await evaluate(`(() => { const target = document.querySelector('[data-file-drop-zone]'); if (!target) return false; const transfer = new DataTransfer(); transfer.items.add(new File([new Uint8Array(${JSON.stringify(bytes)})], 'addition.wasm', {type:'application/wasm'})); target.dispatchEvent(new DragEvent('drop', {bubbles:true, cancelable:true, dataTransfer:transfer})); return true; })()`);
    if (!dropped) throw new Error("packaged app did not expose the file-drop UI");
    for (let attempt = 0; attempt < 100; attempt++) {
        if (await evaluate("document.body.textContent.includes('addition.wasm') && !document.body.textContent.includes('Failed to parse')")) { socket.close(); return; }
        await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error("packaged worker did not reach a parsed UI state");
} finally {
    browser.kill();
    server.close();
}
