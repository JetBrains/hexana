# Zephyr ELF corpus fixtures

Gzipped Cortex-M ELF binaries used by `ElfCorpusObservationsTest` (UI harness,
Thumb-2 decode acceptance) and `ZephyrCorpusB2VerificationTest` (DWARF line-row
resolution in binaryProvider). Tests decompress them to `build/fixtures/` on
first use. Set the `HEXANA_ZEPHYR_CORPUS` environment variable to a local
zephyr-corpus checkout to test against the live corpus instead.

## Provenance (reproducible)

Bundled from the zephyr-corpus repository (commit a30adb9), the ELF-side analog
of skiko.wasm. Each per-sample directory there has a BUILD.md with the exact
board, optimization flags, and Kconfig deltas.

- `apps/**` (17 ELFs, DWARF4/5): Zephyr main v4.4.0-12742-g231e3c5c569 sample
  applications built with Zephyr SDK 1.0.1. Includes:
  - hello_world for nrf52840dk (Renode, nrf52840) and nucleo_f429zi (Renode, generated)
  - hello_world for qemu_cortex_m3 (QEMU lm3s6965evb, Cortex-M3) and mps2/an521/cpu0 (QEMU mps2-an521, Cortex-M33)
  - hello_world for qemu_riscv32 (QEMU virt, RISC-V 32-bit, riscv64-zephyr-elf GCC)
  - hello_world for qemu_cortex_a53 (QEMU virt, Cortex-A53 AArch64, aarch64-zephyr-elf GCC)
  - hello_world for stm32f4_disco (Renode, stm32f4_discovery built-in library platform)
  - hello_world for v2m_beetle (unsupported-target: no QEMU/Renode backend, Cortex-M0)
  - hang_unmodeled for nrf52840dk (hangs-on-unmodeled-hw: spins on reserved addr 0x4002F000 before printk)
  - peripheral_hr nrf52840dk-Os-abi-drift (corpus commit d582bf8): the -Os build with an
    ABI-drift patch (demo pack), diffed against nrf52840dk-Os by
    `AbiDriftCaptureTest` and opened by `BreakpointCaptureTest`
  Each variant directory contains `zephyr.elf.gz`, `zephyr.dts`, and `manifest.json`
  (emulator backend, machine name, boot-banner regex, negativeKind). Rebuild with the corpus
  repo's `tools/build-all.sh`.
- `external/zmk/*.elf` (2 ELFs, DWARF4): firmware images for Seeed XIAO nRF52840 boards
  (`xiao-nrf52840-loa-controller`, `xiao-nrf52840-loa-receiver`), built by the GitHub Actions workflow of the
  open-source project Little On Air (<https://github.com/sayhiben/little-on-air>, `apps/controller` and
  `apps/receiver`, board `xiao_ble/nrf52840`; the build paths inside the ELFs are
  `/home/runner/work/little-on-air/little-on-air/...`). They are plain Zephyr applications: the binaries print
  "Booting Zephyr OS build 3568e1b6d5cd", which is the Zephyr v4.3.0 commit pinned in the project's `west.yml`
  (`3568e1b6d5cdd51a6b964a2a1d6d29200fea2056`), compiled with GCC 12.2.0 (`DW_AT_producer`). There is no ZMK code in
  them (no `zmk` symbol or path in either ELF); the directory name `external/zmk` is historical and is kept
  because tests refer to it. The exact Little On Air commit is not recorded (UNVERIFIED; needed: the corpus
  repository's `external/zmk/README.md`, which named the source).

Traits the tests rely on: Thumb symbol addresses with bit 0 set, M profile in
.ARM.attributes, dotless Zephyr section names, GCC-emitted DWARF v2/v3
line-program headers inside both DWARF4 and DWARF5 builds, and (peripheral_hr
at -Os) 6714 DW_TAG_inlined_subroutine entries as the inline-recovery stress
case.

## Licences

Not everything in this corpus is Apache-2.0. The binaries are included solely as
test inputs. Status legend: VERIFIED = checked against the upstream source named
here (retrieved 2026-11-12); UNVERIFIED = could not be established, the entry says
what is needed. Licence texts are in [`licenses/`](licenses/).

- `apps/**` (and the ELFs under `budget-samples/` built from the same sources): Zephyr sample applications
  `hello_world`, `bluetooth/peripheral_hr` and `subsys/usb/hid-mouse`, Zephyr main
  `v4.4.0-12742-g231e3c5c569` = commit `231e3c5c569b1d2b372a4ed41e5d727d1d2c9e26` (VERIFIED to exist upstream).
  Zephyr and these three samples are `Apache-2.0` (SPDX headers read at that commit;
  [`licenses/Zephyr-Apache-2.0-LICENSE.txt`](licenses/Zephyr-Apache-2.0-LICENSE.txt)). The images also contain
  Zephyr modules pinned by that commit's `west.yml`: `hal_nordic` with nrfx (`BSD-3-Clause`, Nordic Semiconductor,
  [`licenses/nrfx-BSD-3-Clause-LICENSE.txt`](licenses/nrfx-BSD-3-Clause-LICENSE.txt), taken from the nrfx repository
  `master`; the `nrfx` sources in `hal_nordic` carry `SPDX-License-Identifier: BSD-3-Clause`), `cmsis_6` (Apache-2.0,
  repository `LICENSE`) and, in `peripheral_hr`, `tf-psa-crypto` (`Apache-2.0 OR GPL-2.0-or-later`, used under
  Apache-2.0; [`licenses/TF-PSA-Crypto-000d24cb-LICENSE.txt`](licenses/TF-PSA-Crypto-000d24cb-LICENSE.txt)).
  The Zephyr SDK / GCC 14.3.0 runtime and C library code that the toolchain links in is not covered here:
  licences UNVERIFIED (needed: the SDK version's licence list; the README says Zephyr SDK 1.0.1).
  Not Zephyr sources: `hang_unmodeled`, the `nrf52840dk-Os-abi-drift` patch and everything under `budget-samples/`
  that is not a Zephyr build output (`firmware.c`, `linker.ld`, `make_fdt.py`) were written for the corpus; their author
  and licence are UNVERIFIED (needed: the corpus repository authors and its licence; the corpus repository, commit
  `a30adb9`, is not referenced by URL anywhere in this repository).
- `external/zmk/*.elf` (`loa-controller`, `loa-receiver`): the application code is Little On Air, `MIT`,
  Copyright (c) 2026 Ben Menesini
  ([`licenses/little-on-air-MIT-LICENSE.txt`](licenses/little-on-air-MIT-LICENSE.txt), repository `LICENSE` on `main`,
  VERIFIED). They are built on Zephyr v4.3.0 (`Apache-2.0`) with nrfx (`BSD-3-Clause`), CMSIS 6 (`Apache-2.0`),
  Mbed TLS / PSA crypto sources (`Apache-2.0 OR GPL-2.0-or-later`; the Mbed TLS revision pinned by Zephyr v4.3.0
  and the p256-m sources inside it were not checked: UNVERIFIED). These images are not ZMK builds, and ZMK's
  licence does not apply to them.

## budget-samples/

Memory-budget test fixtures, used by the `MemoryBudgetCorpusAcceptanceTest`,
`LinkerMapRegionsTest`, `DtsRegionsTest`, and `FdtRegionsTest` suites in
`binaryProvider/src/jvmTest/`.

Provenance: corpus `budget-samples/` directory (same west workspace as the
`apps/` ELFs above).  ELFs are gzip-compressed (`.elf.gz`);
maps, DTS files, and JSON reports are stored raw.

### Positive samples (ELF + map + dts + rom.json + ram.json)

- `hello_world-nrf52840dk-Os/` — Zephyr hello_world for nRF52840 DK at `-Os`.
  FLASH 1 MB @ 0x0, RAM 256 KB @ 0x20000000.
  romReport.total=21380, ramReport.total=4578.
- `peripheral_hr-nrf52840dk-Os/` — Zephyr Bluetooth HRS app, same board.
  romReport.total=162880, ramReport.total=28473.
- `hello_world-nucleo_f429zi-Os/` — Zephyr hello_world for STM32F429ZI Nucleo at `-Os`.
  FLASH 2 MB @ 0x08000000, RAM 192 KB @ 0x20000000, DTCM 64 KB @ 0x10000000.
  romReport.total=17092, ramReport.total=4424.

### Overlay sample

- `hello_world-nrf52840dk-overlay/` — Two builds of hello_world with the same source:
  `stock/` has FLASH 1 MB, `overlay/` uses `flash_overlay.overlay` to reduce FLASH to 512 KB.
  Exercises map/DTS capacity difference detection.

### Negative / edge-case samples

- `no-siblings/` — Bare ELF, no map or DTS.  Tests LOAD_SEGMENTS fallback.
- `stale-map/` — Os ELF paired with an O2 map.  Tests `linkerMapMatchesElf` returning false.
- `no-memconfig-map/` — Real map with the `Memory Configuration` block removed.
  Tests `parseLinkerMapRegions` returning an empty list gracefully.
- `lld-baremetal/` — LLD-linked baremetal Cortex-M firmware (LLVM 21 ld.lld).
  LLD emits no `Memory Configuration` block; tests that the parser returns empty and
  the budget falls back to manually-supplied regions.  Includes `firmware.map`,
  `firmware.c`, and `linker.ld`.
- `embedded-fdt/` — Minimal ELF with a hand-crafted FDT blob embedded in `.rodata`
  at file offset 0x10004.  Tests `findFdtBlob` and `parseFdtMemoryRegions`.
  Rebuilt via `python3 make_fdt.py`.

## Sibling fixture directories

- `../aprofile/mixed-aprofile.elf.gz`: A-profile mixed ARM/Thumb regression
  fixture for per-symbol mode selection. Built with the Zephyr SDK gcc from the
  bundled `mixed.c`:
  `arm-zephyr-eabi-gcc -march=armv7-a -marm -O1 -nostdlib -ffreestanding -o mixed-aprofile.elf mixed.c`
- `../b2-control/hello-x86_64.elf`: non-ARM control for the DWARF v2/v3
  line-header fix (forces a version 3 line table). Built with clang from the
  bundled `hello.c`:
  `clang --target=x86_64-linux-gnu -fuse-ld=lld -nostdlib -static -gdwarf-3 -O0 hello.c -o hello-x86_64.elf`
