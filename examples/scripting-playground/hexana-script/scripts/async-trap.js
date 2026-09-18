// async-trap.js
// Requires the Scripting v2 Phase 1 branch (feature/scripting-v2-phase1) for
//   the ASYNC_OUTPUT_LOST diagnostic; on master the trailing Promise is silently
//   discarded (no diagnostic, no crash -- the sync print still appears).
// Demonstrates: ASYNC_OUTPUT_LOST warning fired when a script's final expression
//   is a Promise/thenable. The diagnostic fires on the v2 branch; on master the
//   sync output is still visible but the promise is silently lost.
// Binary: any binary.
// Expected outcome:
//   v2 branch: prints "sync output visible" then raises ASYNC_OUTPUT_LOST warning.
//   master:    prints "sync output visible"; Promise is silently discarded.
// API version: v2 for the diagnostic; script itself runs on both.

print("sync output visible");

// Trailing expression is a never-resolving Promise.
// On master: silently discarded.
// On feature/scripting-v2-phase1: ASYNC_OUTPUT_LOST diagnostic is raised.
new Promise(function() {});
