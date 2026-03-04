FROM rust:1-bookworm

RUN rustup toolchain install nightly --profile minimal \
  && rustup target add wasm32-unknown-unknown --toolchain nightly

ENV RUSTUP_TOOLCHAIN=nightly

WORKDIR /work

COPY build-wasm.sh /usr/local/bin/build-wasm.sh
RUN chmod +x /usr/local/bin/build-wasm.sh

ENTRYPOINT ["/usr/local/bin/build-wasm.sh"]
