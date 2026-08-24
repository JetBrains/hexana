// Strings in the binary: the longest ones, and a count per source tag.
//
// Useful for spotting leftover debug paths, embedded URLs, or an unexpectedly
// large string table.

const MIN_LENGTH = 8;
const TOP = 40;

const interesting = binary.strings.filter(s => s.value.length >= MIN_LENGTH);

print("Strings: " + binary.strings.length + " total, " + interesting.length +
      " at least " + MIN_LENGTH + " characters");

const byTag = {};
for (const s of binary.strings) {
    const tag = s.tag || "(untagged)";
    byTag[tag] = (byTag[tag] || 0) + 1;
}
for (const tag of Object.keys(byTag).sort((a, b) => byTag[b] - byTag[a])) {
    print("  " + tag + ": " + byTag[tag]);
}
print("");

const longest = interesting.slice().sort((a, b) => b.value.length - a.value.length).slice(0, TOP);

table("Offset", "Length", "Tag", "Value");
for (const s of longest) {
    row("0x" + s.offset.toString(16), s.value.length, s.tag || "", s.value);
}
