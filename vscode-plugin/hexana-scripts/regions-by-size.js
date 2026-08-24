// Largest regions of the binary, biggest first.
//
// Works for any format: Wasm sections, ELF sections, Mach-O segments, ZIP
// entries — `binary.structure` reports whatever the format's top-level regions
// are.

const regions = binary.structure.slice().sort((a, b) => b.size - a.size);

table("Region", "Offset", "Size", "% of file");
for (const region of regions) {
    row(
        region.name,
        "0x" + region.offset.toString(16),
        region.size,
        (region.size * 100 / binary.size).toFixed(2)
    );
}

const covered = regions.reduce((total, region) => total + region.size, 0);
row("(unaccounted)", "", binary.size - covered, ((binary.size - covered) * 100 / binary.size).toFixed(2));
