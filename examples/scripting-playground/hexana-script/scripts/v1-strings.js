// v1-strings.js
// Demonstrates: string extraction and TSV table output.
//   Path 1: binary.strings, filled by native providers (ELF, Mach-O). Empty for Wasm:
//     the Wasm provider does not implement the strings capability in v1, so there is
//     no Strings tab for Wasm either. This corpus is Wasm-only, so path 2 runs here.
//   Path 2: a small userland scanner over binary.bytes, finding printable ASCII runs.
//     Also a demo of bytes.slice() and offset arithmetic.
// Binary: app-debug.wasm (DWARF carries many strings) or hello.wasm (data segment text).
// Expected outcome: one table of the top strings by length. On app-debug.wasm the
//   source file names and function names from DWARF appear; on hello.wasm the data
//   segment greeting appears.
// API version: v1 (runs on master).

var MIN_LEN = 5;
var MAX_ROWS = 40;

function scanBytes() {
    var found = [];
    var size = binary.bytes.size;
    var CHUNK = 65536;
    var run = "";
    var runStart = -1;
    for (var base = 0; base < size; base += CHUNK) {
        var len = Math.min(CHUNK, size - base);
        var chunk = binary.bytes.slice(base, len);
        for (var i = 0; i < len; i++) {
            var b = chunk[i];
            if (b >= 0x20 && b < 0x7f) {
                if (run.length === 0) runStart = base + i;
                run += String.fromCharCode(b);
            } else {
                if (run.length >= MIN_LEN) found.push({ offset: runStart, value: run });
                run = "";
            }
        }
    }
    if (run.length >= MIN_LEN) found.push({ offset: runStart, value: run });
    return found;
}

var provided = binary.strings;
var source;
var all;
if (provided.length > 0) {
    source = "binary.strings (provider)";
    all = provided.map(function (s) { return { offset: s.offset, value: s.value }; });
} else {
    source = "byte scan (provider exposes no strings for " + binary.format + ")";
    all = scanBytes();
}

all.sort(function (a, b) { return b.value.length - a.value.length; });

print("Strings in " + (binary.name !== null ? binary.name : "unknown") + " via " + source + ":");
table("Length", "Offset (hex)", "Value");
var limit = Math.min(MAX_ROWS, all.length);
for (var i = 0; i < limit; i++) {
    var s = all[i];
    row(s.value.length, "0x" + s.offset.toString(16), s.value.substring(0, 80));
}
print("");
print("Total strings found: " + all.length);
