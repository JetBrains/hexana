# PCAP HTTP replay

Re-sending an HTTP/1.x request captured in a PCAP, optionally edited, from the IntelliJ Packets tab.

## Layers

- `parsing/pcap` (common, all targets):
  - `PcapDissector.reassembleHttpRequest(buffer, packets, index, linkType)` — raw request bytes starting
    in a cleartext packet; appends later same-direction segments until `HttpRequestAssembly` says the
    `Content-Length` / chunked body is whole, a new request starts, or FIN/RST. Duplicate `(seq, len)`
    segments are skipped as retransmissions. Capped at 64 MiB; a head not found within 64 KiB stops.
  - `PcapDissector.tcpEndpoints` — src/dst address+port; the destination is the replay server fallback.
  - `HttpReplay.parseRequest(raw, scheme, serverAddress, serverPort)` → `HttpReplayRequest` (method,
    absolute URL, version, all headers, de-chunked body with content coding kept, `bodyComplete`).
    URL = absolute-form target as-is, else `scheme://Host + target`, else the captured address
    (IPv6 bracketed, default port omitted). `CONNECT` / `OPTIONS *` → `null`.
  - `HttpReplay.replayHeaders` drops connection-specific headers + `Content-Length`/`Transfer-Encoding`.
  - `HttpReplay.toHttpClientFile` renders IntelliJ HTTP Client syntax. `needsBodyFile` is true for
    binary bodies and for text with lines that HTTP Client would parse (`###`, `< `, `> `, `<> `); the
    body then goes through `< ./file`.
- `idea-plugin/.../ui/pcap/`:
  - `HttpReplayDialog` — modeless DialogWrapper; Send keeps it open; binary bodies are not editable and
    are sent as captured; an untouched text body is sent as the captured bytes (text areas normalise CRLF).
  - `HttpReplaySender` — JDK `HttpClient`, HTTP/1.1, redirects NEVER, `CertificateManager.sslContext`
    (IDE-trusted certs), default (IDE) proxy selector. JDK-restricted headers (`Host`, `Expect`, …) are
    dropped, so `Host` always follows the URL.
  - `HttpClientReplayExport` — no compile-time dependency on the HTTP Client plugin
    (`com.jetbrains.restClient`); offered only when `PluginManagerCore.isLoaded`. Writes a scratch
    `hexana-replay-packet-N.http` (+ a `.bin` sidecar in the scratch root for file bodies).
  - Wired in `BinaryEditorView.enrichPcapPacketsTab` as two row context-menu items.

## Limits

- HTTPS uses one decrypted TLS record (from `rowDetailProvider`), so bodies spanning records are cut
  short and marked incomplete. HTTP/2 is not recognised by the dissector, so it is not replayable.
- VS Code / web-ui have no replay yet; the common layer is ready for them (`toHttpClientFile` output is
  also close to the VS Code REST Client format).
