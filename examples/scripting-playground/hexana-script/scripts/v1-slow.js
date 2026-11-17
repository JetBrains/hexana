// v1-slow.js
// Demonstrates: the scripting engine's timeout enforcement.
// Binary: any binary.
// Expected outcome: TIMEOUT diagnostic. The IDE enforces a 60-second timeout;
//   the CLI enforces a 3-second timeout. This loop is genuinely infinite and
//   will never complete normally. Do not wait longer than ~5 seconds in the CLI.
// API version: v1 (runs on master).

print("Starting infinite loop. Expect TIMEOUT diagnostic (CLI: ~3s, IDE: ~60s).");

// Spin forever. The scripting engine's watchdog will interrupt this.
var counter = 0;
while (true) {
    counter = counter + 1;
    // No break, no condition -- intentional infinite loop for timeout demo.
}

// This line is never reached.
print("This should never print. counter=" + counter);
