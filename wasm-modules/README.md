# wasm-modules

Rust modules compiled into `.wasm` binaries.

## Teamcity

You can launch the [build](https://ci.example.invalid/buildConfiguration/HexanaCi_HexanaWasmBuilder) from Teamcity
to get fresh `.wasm` binaries.

## Docker Build (All Modules)

From repository root:

```bash
docker build -t hexana-wasm-modules:build wasm-modules
mkdir -p wasm-modules/out
docker run --rm \
  -v "$PWD/wasm-modules":/src:ro \
  -v "$PWD/wasm-modules/out":/out \
  hexana-wasm-modules:build
```

Docker output:

- `wasm-modules/out/wasmprinter_wrapper.wasm`
- `wasm-modules/out/wat2wasm_full.wasm`
