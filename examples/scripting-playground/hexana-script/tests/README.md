# Scripting Playground: Test Suites

Phase 3 automated test suites will live in this directory. Each suite will exercise a script against a binary from bin/ and assert on the produced output (tables, print lines, diagnostics). Coverage goals include: correct section counts for hello.wasm, string extraction counts for app-debug.wasm, sandbox denial code for v1-denied.js, and timeout behavior for v1-slow.js.
