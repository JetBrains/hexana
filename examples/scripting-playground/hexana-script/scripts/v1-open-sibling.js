// v1-open-sibling.js
// Demonstrates: open(path) to load a sibling binary and compare sizes.
// Binary: run against bin/app-debug.wasm (the sibling is app-release.wasm).
// Expected outcome: prints sizes of both binaries and the delta.
//   Degrades with a clear message when binary.dir is null (no local file path).
// API version: v1 (runs on master).

if (binary.dir === null) {
    print("Skipped: binary.dir is null (no local file path available in this host).");
    print("Run this script against a locally-opened file to see the size comparison.");
} else {
    var siblingPath = binary.dir + "/app-release.wasm";
    print("Opening sibling: " + siblingPath);

    var rel;
    try {
        rel = open(siblingPath);
    } catch (e) {
        print("Could not open sibling (" + siblingPath + "): " + e.message);
        print("Run this script against bin/app-debug.wasm with both files present.");
        rel = null;
    }

    if (rel !== null) {
        print("");
        table("Binary", "Format", "Size (bytes)");
        row(binary.name !== null ? binary.name : "(current)", binary.formatName, binary.size);
        row(rel.name !== null ? rel.name : "app-release.wasm", rel.formatName, rel.size);

        var delta = binary.size - rel.size;
        print("");
        print("Debug vs release delta: " + delta + " bytes  ("
            + (delta >= 0 ? "+" : "") + Math.round(delta / binary.size * 100) + "% of debug size)");
        print("DWARF + name section savings on strip: " + delta + " bytes");
    }
}
