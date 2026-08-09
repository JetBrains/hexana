import { createServer } from "node:http";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, normalize } from "node:path";
import { spawn } from "node:child_process";

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

async function main() {
    const [packageDir, fixture] = process.argv.slice(2);
    if (!packageDir || !fixture) throw new Error("usage: production-package-smoke.mjs <package-dir> <wasm-fixture>");
    const policy = JSON.parse(await readFile(join(packageDir, "hosting-policy.json"), "utf8"));
    const mime = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".wasm": "application/wasm", ".json": "application/json; charset=utf-8" };
    const server = createServer(async (request, response) => {
        const relative = new URL(request.url, "http://localhost").pathname === "/" ? "index.html" : normalize(new URL(request.url, "http://localhost").pathname).replace(/^[/\\]+/, "");
        if (relative.startsWith("..")) return response.writeHead(404).end();
        try {
            const body = await readFile(join(packageDir, relative));
            const extension = relative.slice(relative.lastIndexOf("."));
            response.writeHead(200, { ...policy.headers, "Cache-Control": relative === "index.html" ? policy.cache["index.html"] : relative.startsWith("assets/") ? policy.cache["assets/*"] : policy.cache.evidence, "Content-Type": mime[extension] || "application/octet-stream" });
            response.end(body);
        } catch { response.writeHead(404).end(); }
    });
    let browser;
    let socket;
    let profile;
    try {
        await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
        const port = server.address().port;
        profile = await mkdtemp(join(tmpdir(), "hexana-webapp-smoke-"));
        const candidates = [process.env.CHROME_BIN, "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "google-chrome", "chromium", "chromium-browser"].filter(Boolean);
        for (const candidate of candidates) {
            const child = spawn(candidate, ["--headless=new", "--no-first-run", "--no-default-browser-check", "--remote-debugging-port=0", `--user-data-dir=${profile}`, `http://127.0.0.1:${port}/`], { stdio: "ignore" });
            const failed = await new Promise(resolve => { child.once("error", () => resolve(true)); setTimeout(() => resolve(child.exitCode !== null), 300); });
            if (!failed) { browser = child; break; }
        }
        if (!browser) throw new Error("Chrome/Chromium is required for production-package smoke");
        let devToolsPort;
        for (let attempt = 0; attempt < 80; attempt++) {
            try { devToolsPort = Number((await readFile(join(profile, "DevToolsActivePort"), "utf8")).split("\n")[0]); } catch {}
            if (devToolsPort) break;
            await delay(100);
        }
        if (!devToolsPort) throw new Error("Chrome did not create DevToolsActivePort");
        let page;
        for (let attempt = 0; attempt < 80; attempt++) {
            try { page = (await (await fetch(`http://127.0.0.1:${devToolsPort}/json/list`)).json()).find(target => target.type === "page"); } catch {}
            if (page) break;
            await delay(100);
        }
        if (!page) throw new Error("Chrome DevTools page was not available");
        socket = new WebSocket(page.webSocketDebuggerUrl);
        await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
        let nextId = 1;
        const pending = new Map();
        socket.onmessage = event => { const message = JSON.parse(event.data); const settle = pending.get(message.id); if (settle) { pending.delete(message.id); settle(message); } };
        const evaluate = expression => new Promise((resolve, reject) => {
            const id = nextId++;
            pending.set(id, message => message.error ? reject(new Error(message.error.message)) : message.result?.exceptionDetails ? reject(new Error(message.result.exceptionDetails.text)) : resolve(message.result?.result?.value));
            socket.send(JSON.stringify({ id, method: "Runtime.evaluate", params: { expression, awaitPromise: true, returnByValue: true } }));
        });
        for (let attempt = 0; attempt < 100 && !await evaluate("Boolean(document.querySelector('[data-file-drop-zone]'))"); attempt++) await delay(100);
        const bytes = [...await readFile(fixture)];
        if (!await evaluate(`(() => { const target = document.querySelector('[data-file-drop-zone]'); if (!target) return false; const transfer = new DataTransfer(); transfer.items.add(new File([new Uint8Array(${JSON.stringify(bytes)})], 'addition.wasm', {type:'application/wasm'})); target.dispatchEvent(new DragEvent('drop', {bubbles:true, cancelable:true, dataTransfer:transfer})); return true; })()`)) throw new Error("packaged app did not expose the file-drop UI");
        for (let attempt = 0; attempt < 120; attempt++) {
            const state = await evaluate("document.body.textContent");
            if (state.includes("Summary") && state.includes("Imports: 1") && state.includes("Sections: 5") && !state.includes("Parsing…") && !state.includes("Failed to parse") && !state.includes("Timed out")) return;
            await delay(100);
        }
        throw new Error("timed out waiting for parsed addition.wasm UI: expected Summary, Imports: 1, and Sections: 5 without parsing/failure/timeout");
    } finally {
        if (socket) socket.close();
        if (browser) { browser.kill(); await new Promise(resolve => browser.once("exit", resolve)); }
        await new Promise(resolve => server.close(resolve));
        if (profile) await rm(profile, { recursive: true, force: true });
    }
}

await main();
