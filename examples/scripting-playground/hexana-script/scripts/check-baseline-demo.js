// check-baseline-demo.js
// Requires the Scripting v2 Phase 2A API.
// Demonstrates: the triage loop for a check that always emits one ERROR finding.
//   Step 1: run without --update-baselines -> exit 1, baselineState "new".
//   Step 2: run with --update-baselines   -> exit 0, baseline file written.
//   Step 3: run without --update-baselines -> exit 0, baselineState "unchanged".
// Binary: any Wasm binary; shown here against bin/app-debug.wasm.
// Baselines dir: .hexana/baselines (created in the playground root; gitignored).
// API version: v2 Phase 2A.
//
// Step 1 -- first run, exit 1 expected:
//   hexana-cli-script run-script bin/app-debug.wasm \
//     --script hexana-script/scripts/check-baseline-demo.js \
//     --format sarif --telemetry-enabled false \
//     --baselines-dir .hexana/baselines
//
// Step 2 -- baseline the finding, exit 0 expected:
//   hexana-cli-script run-script bin/app-debug.wasm \
//     --script hexana-script/scripts/check-baseline-demo.js \
//     --format sarif --telemetry-enabled false \
//     --baselines-dir .hexana/baselines --update-baselines
//
// Step 3 -- re-run, exit 0 expected (finding is UNCHANGED):
//   hexana-cli-script run-script bin/app-debug.wasm \
//     --script hexana-script/scripts/check-baseline-demo.js \
//     --format sarif --telemetry-enabled false \
//     --baselines-dir .hexana/baselines

hexana.defineCheck({
    id: "user:always-fails",
    severity: "error",
    description: "A permanent check used to demonstrate the baseline triage loop.",
    run: function(binary, report) {
        report.fail(
            "This check always fails. Baseline it with --update-baselines to suppress the exit code."
        );
        console.log("user:always-fails: emitted one ERROR finding");
    }
});
