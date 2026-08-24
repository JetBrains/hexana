// Classic hex dump of the first bytes of the file, plus a magic-number check.
//
// Shows the byte-level helpers: at/hex/ascii/u32/indexOf.

const BYTES = 256;
const PER_ROW = 16;

print(binary.formatName + ", " + binary.size + " bytes");
print("Magic: " + binary.bytes.hex(0, Math.min(8, binary.size)));

const debugInfo = binary.bytes.indexOf(".debug_info");
print(debugInfo >= 0
    ? "Found .debug_info at 0x" + debugInfo.toString(16)
    : "No .debug_info string in the file");
print("");

const limit = Math.min(BYTES, binary.size);
table("Offset", "Bytes", "ASCII");
for (let offset = 0; offset < limit; offset += PER_ROW) {
    const length = Math.min(PER_ROW, limit - offset);
    row(
        "0x" + offset.toString(16).padStart(8, "0"),
        binary.bytes.hex(offset, length),
        binary.bytes.ascii(offset, length)
    );
}
