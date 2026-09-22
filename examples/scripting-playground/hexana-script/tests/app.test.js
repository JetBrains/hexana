// app.test.js
// Test suite for bin/app-debug.wasm.
//
// All assertions use exact values from bin/MANIFEST.md.
//
// Run (from examples/scripting-playground/):
//   hexana-cli-script test hexana-script/tests/app.test.js \
//     --artifact app=bin/app-debug.wasm \
//     --telemetry-enabled false

import { assertEquals, assertTrue, assertNull } from "./helpers/expect.mjs";

const app = hexana.artifact("app", { formats: ["wasm"] });

// -------------------------------------------------------------------------
// Basic shape
// -------------------------------------------------------------------------

describe("app-debug.wasm shape", () => {
    test("size is exactly 4703 bytes", () => {
        assertEquals(app.size, 4703, "binary size");
    });

    test("format is wasm", () => {
        assertEquals(app.format, "wasm", "format id");
    });

    test("name is app-debug.wasm", () => {
        assertEquals(app.name, "app-debug.wasm", "file name");
    });

    // -------------------------------------------------------------------------
    // Exports: 5 total (memory + 4 funcs)
    // -------------------------------------------------------------------------

    describe("exports", () => {
        test("exactly 5 exports", () => {
            const exps = app.exports();
            assertEquals(exps.length, 5, "export count");
        });

        test("export names match MANIFEST", () => {
            const exps = app.exports();
            const names = exps.map(e => e.name).sort();
            const expected = ["_initialize", "compute_fib", "greeting_byte", "greeting_len", "memory"].sort();
            assertEquals(JSON.stringify(names), JSON.stringify(expected), "export names");
        });

        test("memory export has kind memory", () => {
            const mem = app.exports().find(e => e.name === "memory");
            assertTrue(mem !== undefined, "memory export exists");
            assertEquals(mem.kind, "memory", "memory export kind");
        });

        test("func exports have kind func", () => {
            const funcNames = ["_initialize", "greeting_len", "compute_fib", "greeting_byte"];
            const exps = app.exports();
            for (const name of funcNames) {
                const e = exps.find(ex => ex.name === name);
                assertTrue(e !== undefined, name + " export present");
                assertEquals(e.kind, "func", name + " export kind");
            }
        });
    });
});

// -------------------------------------------------------------------------
// Imports: 0 (wasi-libc is statically linked)
// -------------------------------------------------------------------------

describe("imports", () => {
    test("no imports (wasi-libc is statically linked)", () => {
        const imps = app.imports();
        assertEquals(imps.length, 0, "import count");
    });
});

// -------------------------------------------------------------------------
// Functions: 8 total (no imports + 8 defined)
// -------------------------------------------------------------------------

describe("functions", () => {
    test("total function count is 8", () => {
        const page = app.functions({ limit: 200 });
        assertEquals(page.total, 8, "total function count");
    });

    test("all functions are code (no imports)", () => {
        const page = app.functions({ limit: 200 });
        for (const fn of page.items) {
            assertEquals(fn.source, "code", "funcidx " + fn.funcidx + " source");
        }
    });

    test("functions have non-negative funcidx", () => {
        const page = app.functions({ limit: 200 });
        for (const fn of page.items) {
            assertTrue(fn.funcidx >= 0, "funcidx " + fn.funcidx + " is non-negative");
        }
    });
});

// -------------------------------------------------------------------------
// Async test: SHA-256 of the first 8 bytes (Wasm magic + version)
// -------------------------------------------------------------------------

describe("async crypto", () => {
    test("SHA-256 digest of file header completes and has 32 bytes", async () => {
        const headerBytes = app.bytes.slice(0, 8);
        const input = new Uint8Array(headerBytes);
        const hashBuf = await crypto.subtle.digest("SHA-256", input);
        const hashBytes = new Uint8Array(hashBuf);
        assertEquals(hashBytes.length, 32, "SHA-256 output size");
        // Wasm magic header: 00 61 73 6d 01 00 00 00
        // First byte of the raw slice must be 0x00 (Wasm magic byte 0)
        assertEquals(input[0], 0x00, "magic byte 0");
        assertEquals(input[1], 0x61, "magic byte 1 (a)");
        assertEquals(input[2], 0x73, "magic byte 2 (s)");
        assertEquals(input[3], 0x6d, "magic byte 3 (m)");
        console.log(
            "SHA-256(header[:8]) = " +
            Array.from(hashBytes).map(b => b.toString(16).padStart(2, "0")).join("").slice(0, 16) + "..."
        );
    });

    test("setTimeout resolves in async test", async () => {
        const before = Date.now();
        await new Promise((resolve) => setTimeout(resolve, 0));
        const after = Date.now();
        assertTrue(after >= before, "time does not go backwards");
    });
});
