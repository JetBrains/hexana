# Scripted binary provider demo

A minimal project showing a binary provider written in JavaScript:

- `.hexana/providers/firmware-image.js` defines the provider `user:acme-fw` ("ACME firmware
  image") with `hexana.defineProvider`, and a "Header" tab with `hexana.defineTab`.
- `.hexana/file_associations.json` assigns that provider to `firmware-image.bin`.
- `firmware-image.bin` is a 144-byte image in the made-up format the script reads: a 16-byte
  header (magic `FWIM`, version 2, entry point `0x8000`, payload size 96), a 96-byte payload,
  and a 32-byte signature.

Open **this folder** as the project in IntelliJ or VS Code (provider scripts are loaded from
the opened project's own `.hexana/`, so opening the whole Hexana repository is not enough),
trust it when asked, and open `firmware-image.bin`. The format badge reads
"ACME firmware image", the hex view shows the Header / Payload / Signature regions, and the
Header tab lists the decoded fields.
