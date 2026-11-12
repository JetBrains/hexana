// flags-demo.js
// Requires the Scripting v2 Phase 2A API.
// Demonstrates: hexana.defineFlags with a values-enum flag, an int flag,
//   and a path-typed flag; hexana.args, hexana.flags, hexana.inputs.
// Binary: any Wasm binary; shown here against bin/hello.wasm.
// API version: v2 Phase 2A.
//
// Run 1 -- defaults only, exit 0:
//   hexana-cli-script run-script bin/hello.wasm \
//     --script hexana-script/scripts/flags-demo.js \
//     --format raw --telemetry-enabled false
//
// Run 2 -- flags set, extra input, exit 0:
//   hexana-cli-script run-script bin/hello.wasm \
//     --script hexana-script/scripts/flags-demo.js \
//     --format raw --telemetry-enabled false \
//     --input bin/hello.wasm \
//     -- --mode fast --limit 42 --file bin/hello.wasm
//
// Run 3 -- undeclared flag, exit 2 expected:
//   hexana-cli-script run-script bin/hello.wasm \
//     --script hexana-script/scripts/flags-demo.js \
//     --format raw --telemetry-enabled false \
//     -- --undeclared-flag value

hexana.defineFlags({
    // values-enum flag: one of "fast", "slow", "auto"; default "auto"
    mode: {
        long: "mode",
        values: ["fast", "slow", "auto"],
        default: "auto"
    },
    // int flag with a default
    limit: {
        long: "limit",
        type: "int",
        default: 10
    },
    // path flag: default null (absent); when set, open() reads it
    file: {
        long: "file",
        type: "path",
        default: null
    }
});

// Print the raw argument vector. args[0] is the script path.
console.log("hexana.args: " + JSON.stringify(hexana.args));

// Print resolved flag values.
print("flags.mode  = " + hexana.flags.mode);
print("flags.limit = " + hexana.flags.limit);
print("flags.file  = " + hexana.flags.file);

// Print the inputs count.
// inputs[0] is always the primary binary (same as global binary).
// Extra inputs are appended in --input order.
print("inputs.length = " + hexana.inputs.length);

// When the path flag is set, open the file and print its size.
if (hexana.flags.file !== null) {
    var extra = open(hexana.flags.file);
    print("file.size = " + extra.size + " bytes  format = " + extra.formatName);
}
