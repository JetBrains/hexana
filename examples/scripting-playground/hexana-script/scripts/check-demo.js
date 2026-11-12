// check-demo.js
// Requires the Scripting v2 Phase 2A API.
// Demonstrates: hexana.defineCheck with two checks against bin/app-debug.wasm.
//   Check 1 (ERROR, funcidx anchor): flag any function whose code body is
//     fewer than 5 bytes. app-debug.wasm has one such function: __wasm_call_ctors
//     at funcidx 0, codeSize=3.
//   Check 2 (INFO, byte-range anchor): note that the binary contains DWARF
//     debug sections, anchored to the Wasm file header (first 8 bytes).
// Expected outcome:
//   raw mode  -- exit 1 (the ERROR finding); stderr has console.log lines.
//   sarif mode -- exit 1; stdout is SARIF JSON with ruleId, level, baselineState.
// API version: v2 Phase 2A.
//
// Run (raw, exit 1 expected):
//   hexana-cli-script run-script bin/app-debug.wasm \
//     --script hexana-script/scripts/check-demo.js \
//     --format raw --telemetry-enabled false \
//     --baselines-dir /tmp/check-demo-baselines
//
// Run (sarif -- spot-check ruleId / level / baselineState):
//   hexana-cli-script run-script bin/app-debug.wasm \
//     --script hexana-script/scripts/check-demo.js \
//     --format sarif --telemetry-enabled false \
//     --baselines-dir /tmp/check-demo-baselines

// Check 1: ERROR -- any function whose code body is fewer than 5 bytes.
// On app-debug.wasm this fires on __wasm_call_ctors (funcidx 0, codeSize 3).
hexana.defineCheck({
    id: "user:no-trivial-functions",
    severity: "error",
    description: "Flag functions with code bodies under 5 bytes. They are usually dead stubs.",
    run: function(binary, report) {
        var page = binary.functions({ limit: 100 });
        for (var i = 0; i < page.items.length; i++) {
            var fn = page.items[i];
            if (fn.source === "code" && fn.codeSize !== undefined && fn.codeSize < 5) {
                report.fail(
                    "Function " + (fn.name !== null ? fn.name : "(unnamed)") +
                        " has a " + fn.codeSize + "-byte body (< 5). " +
                        "This is likely a dead stub.",
                    { at: { funcidx: fn.funcidx } }
                );
            }
        }
        console.log("user:no-trivial-functions: scanned " + page.items.length + " functions");
    }
});

// Check 2: INFO -- the binary contains DWARF custom sections (debug build).
// Anchored to the Wasm file magic header (offset 0, length 8).
hexana.defineCheck({
    id: "user:debug-sections-present",
    severity: "info",
    description: "Notes when a Wasm binary carries DWARF debug sections.",
    run: function(binary, report) {
        var sections = binary.structure;
        var debugNames = [];
        for (var i = 0; i < sections.length; i++) {
            if (sections[i].name.indexOf(".debug_") === 0) {
                debugNames.push(sections[i].name);
            }
        }
        if (debugNames.length > 0) {
            report.info(
                "Binary contains " + debugNames.length + " DWARF section(s): " +
                    debugNames.join(", ") + ". Strip before release.",
                { at: { offset: 0, length: 8 } }
            );
        }
        console.log("user:debug-sections-present: found " + debugNames.length + " debug section(s)");
    }
});
