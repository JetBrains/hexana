# PCAP HTTP replay

Re-sending an HTTP/1.x request captured in a PCAP, optionally edited, from the IntelliJ Packets tab.

## Layers

- `parsing/pcap` (common, all targets):
  - `PcapDissector.reassembleHttpRequest(buffer, packets, index, linkType)` — raw request bytes starting
    in a cleartext packet; places later same-direction segments by TCP sequence number (mod 2^32) until
    `HttpRequestAssembly` says the `Content-Length` / chunked body is whole, or FIN/RST. Retransmitted
    bytes are dropped, overlaps trimmed, out-of-order segments held until their gap fills; output stops
    at a gap never filled. No "next request starts" heuristic (a body may begin with `GET `).
    Capped at 64 MiB; a head not found within 64 KiB stops.
  - `PcapDissector.tcpEndpoints` — src/dst address+port; the destination is the replay server fallback.
  - `HttpReplay.parseRequest(raw, scheme, serverAddress, serverPort)` → `HttpReplayRequest` (method,
    absolute URL, version, all headers, de-chunked body with content coding kept, `bodyComplete`).
    URL = absolute-form target as-is, else `scheme://Host + target`, else the captured address
    (IPv6 bracketed, default port omitted). `CONNECT` / `OPTIONS *` → `null`.
  - `HttpReplay.replayHeaders` drops connection-specific headers + `Content-Length`/`Transfer-Encoding`,
    any header named in a `Connection` value (RFC 9110 §7.6.1), and `Proxy-Authorization` (a replay goes
    straight to the origin, so proxy credentials must not leak); `sendableHeaders` applies the same and
    also drops `Host`/`Expect`, which the JDK client and Node `fetch` own.
  - `HttpReplay.isValidMethod` — RFC tchar token; shared by `parseRequest` and both dialogs.
  - `HttpReplay.editableBodyText` / `uneditableBodySummary` — bodies over `MAX_EDITABLE_BODY_BYTES`
    (1 MiB) or binary are shown as a summary and sent as captured, so dialogs never decode/render huge
    bodies on the UI thread.
  - `HttpReplay.capturedRequest(buffer, packets, index, linkType) { plaintext }` → `CapturedHttpRequest`
    (request, packet number, origin line, `.http`/body file names) and `mayHoldRequest` (cheap menu
    visibility check) — the row → request logic every host shares.
  - `HttpReplay.toHttpClientFile` renders IntelliJ HTTP Client syntax. `needsBodyFile` is true for
    binary bodies, bodies over 1 MiB (keeps the EDT-created scratch document small), and for text with lines that HTTP Client would parse (`###`, `< `, `> `, `<> `); the
    body then goes through `< ./file`.
- `idea-plugin/.../ui/pcap/`:
  - `HttpReplayDialog` — modeless DialogWrapper; Send keeps it open; binary bodies are not editable and
    are sent as captured; an untouched text body is sent as the captured bytes (text areas normalise CRLF).
  - `HttpReplaySender` — JDK `HttpClient`, HTTP/1.1, redirects NEVER, `CertificateManager.sslContext`
    (IDE-trusted certs), default (IDE) proxy selector. JDK-restricted headers (`Host`, `Expect`, …) are
    dropped, so `Host` always follows the URL. Response read via `ofInputStream`, capped at
    `MAX_RESPONSE_BYTES` (16 MiB, `bodyTruncated`); `decompressHttpBody(…, maxBytes)` bounds inflation.
  - `HttpClientReplayExport` — no compile-time dependency on the HTTP Client plugin
    (`com.jetbrains.restClient`); offered only when `PluginManagerCore.isLoaded`. Writes a scratch
    `hexana-replay-packet-N.http` (+ a `.bin` sidecar in the scratch root for file bodies).
  - Wired in `BinaryEditorView.enrichPcapPacketsTab` as two row context-menu items, plus a run-icon
    gutter (`rowAction`, `RowActionKind.Run`) that opens `HttpReplayDialog`; its tooltip provider
    returns `null` (icon hidden) unless `HttpReplay.mayHoldRequest` says the row may hold a request.
    VS Code's `enrichPcapPacketsTab` adds the same gutter when `Host.canReplayHttp`.

- VS Code (`web-ui` + `vscode-plugin`):
  - `web-ui/.../HttpReplayDialog.kt` — Compose HTML dialog (overlay click does not close it);
    `editedReplayRequest` holds the same untouched-body rule as IntelliJ; `Host.openCapturedRequestFile`
    renders the `.http` text in Kotlin. Offered only when `Host.canReplayHttp` (false in the webapp:
    CORS, no editor).
  - Messages: `http-replay-send` → `http-replay-response` (one reply per request, intercepted in
    `VscodeMessageListener` and settled through `HttpReplaySends`, 75 s deadline), and
    `open-http-request-file`.
  - `vscode-plugin/src/httpReplay.ts` (no `vscode` import, unit-tested) — Node `fetch` with
    `redirect: "manual"` and a 60 s timeout; `fetch` undoes content coding, so the body arrives
    decoded. The body is streamed and capped at `HTTP_REPLAY_MAX_RESPONSE_BYTES` (16 MiB,
    `bodyTruncated` carried through the bridge to `HttpReplayResponse`). `writeHttpRequestFile` writes into a fresh `os.tmpdir()/hexana-replay-*` dir (a real file,
    so REST Client resolves `< ./body`). The provider opens it and offers `humao.rest-client` via
    `extension.open` when that extension is missing. REST Client reads the same syntax as IntelliJ's.

## Limits

- HTTPS uses one decrypted TLS record (from `rowDetailProvider`), so bodies spanning records are cut
  short and marked incomplete. HTTP/2 is not recognised by the dissector, so it is not replayable.
- The browser webapp has no replay. VS Code replays use Node's TLS store, not VS Code's, so a
  self-signed server needs `NODE_EXTRA_CA_CERTS`.

## Protocol filter (Packets tab)

`PcapTableTabs.protocolFilters` gives the shared Packets tab one `PredefinedFilter` per protocol family
(`pcapProtocolFamily`: all TLS versions → `TLS`), listed from the header-only `PcapDissector.protocolInfo`
pass, with `defaultFilterShowAll = true` (no filter by default). A row matches if its wire family *or* its
displayed Protocol cell family matches, so a decrypted HTTPS row is under both TLS and HTTP; HTTP is
offered whenever TLS is present and a key log is loaded. No filter when only one family is present.
The displayed-protocol fallback decrypts, so a TLS-wire row consults it only for the HTTP filter (other
filters decide TLS rows by wire family); non-TLS rows still consult it, since `isHttpPayload` (2-byte
prefix) is looser than the column's parsed HTTP head, and they never decrypt.
Host enrichers rebuild the descriptor field by field, so they must carry `defaultFilterShowAll` too.

