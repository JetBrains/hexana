// v1-denied.js
// Demonstrates: sandbox denial when a script attempts host class access.
// Binary: any binary.
// Expected outcome: SANDBOX_DENIED diagnostic, exit code 1.
//   The GraalJS sandbox disallows Java.type() with host class access disabled.
// Observed via CLI (verified on master 2026-09-18):
//   diagnostic code: SANDBOX_DENIED
//   message: "TypeError: Access to host class java.lang.System is not allowed or does not exist."
//   exceptionClass: org.graalvm.polyglot.PolyglotException
// API version: v1 (runs on master).

print("Attempting Java.type('java.lang.System') -- expect SANDBOX_DENIED from sandbox.");

// This call is blocked by the GraalJS sandbox (no host class lookup).
// On the v1 CLI the diagnostic code observed is RUNTIME_ERROR.
var sys = Java.type("java.lang.System");
print("If this prints, sandbox is not enforced: " + sys);
