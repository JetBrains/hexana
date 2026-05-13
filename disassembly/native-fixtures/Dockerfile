FROM debian:bookworm-slim

RUN apt-get update \
  && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ca-certificates \
    llvm \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /work

ENV SRC_DIR=/src
ENV OUT_DIR=/out

COPY build-disassembly-fixtures.sh /usr/local/bin/build-disassembly-fixtures.sh
RUN chmod +x /usr/local/bin/build-disassembly-fixtures.sh

ENTRYPOINT ["/usr/local/bin/build-disassembly-fixtures.sh"]
