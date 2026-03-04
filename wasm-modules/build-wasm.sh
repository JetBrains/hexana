#!/usr/bin/env bash
set -euo pipefail

SRC_DIR=/src
WORK_DIR=/work
OUT_DIR=/out
TARGET=wasm32-unknown-unknown

# Build matrix in "<module_dir>:<expected_wasm_artifact>" format.
# To add another module, append one more entry with its crate directory
# (relative to wasm-modules root) and output file name from target/release.
MODULES=(
  "wasmprinter_wrapper:wasmprinter_wrapper.wasm"
  "wat2wasm:wat2wasm_full.wasm"
)

if [ ! -d "$SRC_DIR" ]; then
  echo "Expected sources mounted at /src" >&2
  exit 1
fi

mkdir -p "$WORK_DIR" "$OUT_DIR"
# Copy mounted sources into a writable workspace for cargo builds. We don't wanna make read only files on local repos
tar -C "$SRC_DIR" -cf - . | tar -C "$WORK_DIR" -xf -

# Build each module and export its release wasm artifact to /out.
for entry in "${MODULES[@]}"; do
  IFS=: read -r module_dir module_wasm <<<"$entry"
  manifest_path="$WORK_DIR/$module_dir/Cargo.toml"
  output_path="$WORK_DIR/$module_dir/target/$TARGET/release/$module_wasm"

  if [ ! -f "$manifest_path" ]; then
    echo "Missing Cargo.toml for module '$module_dir' at '$manifest_path'" >&2
    exit 1
  fi

  cargo build --manifest-path "$manifest_path" --target "$TARGET" --release

  if [ ! -f "$output_path" ]; then
    echo "Expected artifact '$output_path' was not produced" >&2
    exit 1
  fi

  cp "$output_path" "$OUT_DIR/$module_wasm"
done
