
  `rustup run nightly rustc --version`
  `rustup toolchain install nightly --force`
  `rustup +nightly target add wasm32-unknown-unknown`
  `export PATH="$HOME/.rustup/toolchains/nightly-aarch64-apple-darwin/bin:$PATH"`
  `cargo build --target wasm32-unknown-unknown --release`
