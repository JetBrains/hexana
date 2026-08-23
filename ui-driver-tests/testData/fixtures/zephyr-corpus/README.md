# Zephyr/ZMK ELF corpus fixtures

Gzipped Cortex-M ELF binaries used by `ElfCorpusObservationsTest` (UI harness,
Thumb-2 decode acceptance) and `ZephyrCorpusB2VerificationTest` (DWARF line-row
resolution in binaryProvider). Tests decompress them to `build/fixtures/` on
first use. Set the `HEXANA_ZEPHYR_CORPUS` environment variable to a local
zephyr-corpus checkout to test against the live corpus instead.

## Provenance (reproducible)

Bundled from the zephyr-corpus repository (commit a30adb9), the ELF-side analog
of skiko.wasm. Each per-sample directory there has a BUILD.md with the exact
board, optimization flags, and Kconfig deltas.

- `apps/**` (9 ELFs, DWARF5): Zephyr main v4.4.0-12742-g231e3c5c569 sample
  applications (hello_world, peripheral_hr, usb_hid-mouse) built with Zephyr
  SDK 1.0.1 (arm-zephyr-eabi GCC) for nrf52840dk/nucleo_f429zi at the
  optimization level named by the directory. Rebuild with the corpus repo's
  `tools/build-all.sh`.
- `external/zmk/*.elf` (3 ELFs, DWARF4): public Zephyr BLE firmware images for
  Seeed XIAO nRF52840 boards (ZMK-adjacent projects; real ZMK CI ships only
  .uf2). See `external/zmk/README.md` in the corpus repo for the source
  repositories.

Traits the tests rely on: Thumb symbol addresses with bit 0 set, M profile in
.ARM.attributes, dotless Zephyr section names, GCC-emitted DWARF v2/v3
line-program headers inside both DWARF4 and DWARF5 builds, and (peripheral_hr
at -Os) 6714 DW_TAG_inlined_subroutine entries as the inline-recovery stress
case.

All Zephyr and ZMK sources are Apache-2.0. The binaries are included solely as
test inputs.

## Sibling fixture directories

- `../aprofile/mixed-aprofile.elf.gz`: A-profile mixed ARM/Thumb regression
  fixture for per-symbol mode selection. Built with the Zephyr SDK gcc from the
  bundled `mixed.c`:
  `arm-zephyr-eabi-gcc -march=armv7-a -marm -O1 -nostdlib -ffreestanding -o mixed-aprofile.elf mixed.c`
- `../b2-control/hello-x86_64.elf`: non-ARM control for the DWARF v2/v3
  line-header fix (forces a version 3 line table). Built with clang from the
  bundled `hello.c`:
  `clang --target=x86_64-linux-gnu -fuse-ld=lld -nostdlib -static -gdwarf-3 -O0 hello.c -o hello-x86_64.elf`
