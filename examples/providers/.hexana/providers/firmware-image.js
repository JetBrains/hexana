// Example provider script, part of the demo project in examples/providers/: open that folder
// in IntelliJ or VS Code and open firmware-image.bin, which ../file_associations.json already
// assigns to this provider.
//
// To use it in a project of your own, copy it to `.hexana/providers/`, then pick
// "ACME firmware image" from the format badge's parser menu on a firmware file (or add
// `"path/to/image.bin": "user:acme-fw"` to `.hexana/file_associations.json`).
//
// The format is made up for the example: a 16-byte header — magic "FWIM", then three
// little-endian u32s: version, entry point, payload size — the payload, and a 32-byte
// signature trailer.

const HEADER_SIZE = 16;
const SIGNATURE_SIZE = 32;

/** Reads the header fields, or null when the file is too short to have one. */
function header(binary) {
  const bytes = binary.bytes;
  if (bytes.size < HEADER_SIZE) return null;
  return {
    magic: bytes.ascii(0, 4),
    version: bytes.u32(4, true),
    entry: bytes.u32(8, true),
    payloadSize: bytes.u32(12, true),
  };
}

hexana.defineProvider({
  id: "user:acme-fw",
  name: "ACME firmware image",
  description: "Header, payload and signature of an ACME boot image.",
  // Raw bytes underneath; use "elf", "pe", ... to keep a built-in parser's tabs.
  base: "generic",
  parse(binary) {
    const h = header(binary);
    if (!h) return { regions: [] };
    // Regions past the end of the file (a truncated image) are dropped by Hexana and
    // reported in the format badge, so they need no bounds checks here.
    return {
      regions: [
        { name: "Header", offset: 0, length: HEADER_SIZE, tab: "user:Header" },
        { name: "Payload", offset: HEADER_SIZE, length: h.payloadSize },
        { name: "Signature", offset: HEADER_SIZE + h.payloadSize, length: SIGNATURE_SIZE },
      ],
    };
  },
});

hexana.defineTab({
  name: "user:Header",
  // Shown for files opened with the provider above; list built-in ids ("elf", "generic", ...)
  // or "*" to attach a tab to other files too.
  appliesTo: ["user:acme-fw"],
  columns: [
    { name: "Field", type: "string" },
    { name: "Value", type: "string" },
    { name: "Offset", type: "offset" },
  ],
  rows(binary) {
    const h = header(binary);
    if (!h) return { rows: [] };
    return {
      rows: [
        { cells: ["Magic", h.magic, 0] },
        { cells: ["Version", String(h.version), 4] },
        { cells: ["Entry point", "0x" + h.entry.toString(16), 8] },
        { cells: ["Payload size", String(h.payloadSize), 12] },
      ],
    };
  },
});
