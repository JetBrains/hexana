FROM debian:bookworm-slim

RUN apt-get update \
  && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ca-certificates \
    clang \
    lld \
    llvm \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /work

ENV SRC_DIR=/src
ENV OUT_DIR=/out

COPY build-native-fixtures.sh /usr/local/bin/build-native-fixtures.sh
RUN chmod +x /usr/local/bin/build-native-fixtures.sh

ENTRYPOINT ["/usr/local/bin/build-native-fixtures.sh"]
