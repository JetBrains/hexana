# webapp_server

A WebAssembly Component Model binary that embeds the Hexana webapp static assets
and serves them over HTTP via `wasi:http/incoming-handler`.

## What it is

The crate compiles to a single `.wasm` component that:

- Exports `wasi:http/incoming-handler@0.2.x` (the `wasi:http/proxy` world).
- Embeds the production webapp assets at compile time via `build.rs` and `include_bytes!`.
- Routes requests: `/` resolves to `/index.html`; exact path match otherwise; 404 for anything
  not in the allowlist.
- Sets correct content-type headers and streams large files in 4 KB chunks.

The allowlisted assets are: `index.html`, `robots.txt`, `boot.js`, `webapp.js`, `theme.css`, `editor.css`,
`wasmprinter_wrapper.wasm`, and everything under `composeResources/`.

Files intentionally excluded: `webapp.js.map`, `mpp-demo.wasm`, `skiko.wasm`,
`add.component.wasm`.

## Build locally

Requires: rustc with the `wasm32-wasip2` target, and a built webapp dist:

```bash
cd wasm-modules/webapp_server
HEXANA_WEBAPP_DIST=../../webapp/build/dist/js/productionExecutable \
  cargo build --release --target wasm32-wasip2
```

The component is written to:
`target/wasm32-wasip2/release/webapp_server.wasm`

## Run

```bash
wasmtime serve -S cli=y --addr 0.0.0.0:8124 \
  target/wasm32-wasip2/release/webapp_server.wasm
```

The `-S cli=y` flag enables the WASI CLI APIs that the Rust standard library
requires (environment, stderr, exit). This is separate from the HTTP server
functionality and does not expose any host filesystem or network beyond what
the proxy world already provides.

## Gradle tasks

From the repo root:

```bash
./gradlew :webapp:buildComponent    # build + copy to webapp/build/component/
./gradlew :webapp:serveComponent    # build then serve on 0.0.0.0:8124
```

## Inspect WIT world

```bash
wasm-tools component wit webapp_server.wasm
```

Should show `export wasi:http/incoming-handler@0.2.x`.
