// v1-overview.js
// Demonstrates: binary.structure (section table), binary.tabs (available tab names).
// Binary: any supported format; shown here against hello.wasm and app-debug.wasm.
// Expected outcome: two tables -- structure regions and tab names -- printed to the script tab.
// API version: v1 (runs on master).

print("File:", binary.name, "  Format:", binary.formatName, "  Size:", binary.size, "bytes");
print("");

// Structure table: one row per top-level region (sections for Wasm)
print("Structure regions:");
table("Region", "Offset (hex)", "Size (bytes)", "Editor tab");
for (var i = 0; i < binary.structure.length; i++) {
    var r = binary.structure[i];
    row(r.name, "0x" + r.offset.toString(16), r.size, r.tab !== null ? r.tab : "(none)");
}

print("");

// Tabs table: what tabs Hexana can show for this binary
print("Available tabs:");
table("Index", "Tab name");
for (var j = 0; j < binary.tabs.length; j++) {
    row(j, binary.tabs[j]);
}

print("");
print("Done. " + binary.structure.length + " regions, " + binary.tabs.length + " tabs.");
