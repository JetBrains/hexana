// Hexana webapp server — wasi:http/proxy component.
//
// Embeds the production webapp assets at compile time and serves them in
// response to incoming HTTP requests. Intended to be run as:
//
//   wasmtime serve hexana-webapp.component.wasm

use wasi::exports::http::incoming_handler::Guest;
use wasi::http::types::{
    Fields, IncomingRequest, OutgoingBody, OutgoingResponse, ResponseOutparam,
};

include!(concat!(env!("OUT_DIR"), "/assets.rs"));

// Chunk size for streaming writes: stay safely below the 4096-byte limit
// imposed by wasi:io/streams blocking-write-and-flush.
const CHUNK: usize = 4000;

struct Handler;

impl Guest for Handler {
    fn handle(request: IncomingRequest, response_out: ResponseOutparam) {
        let raw_path = request.path_with_query().unwrap_or_else(|| "/".into());
        // Strip query string.
        let path = match raw_path.find('?') {
            Some(i) => &raw_path[..i],
            None => &raw_path,
        };
        // Normalise bare root.
        let path = if path.is_empty() { "/" } else { path };

        // Resolve "/" to "/index.html".
        let lookup = if path == "/" { "/index.html" } else { path };

        match find_asset(lookup) {
            Some(asset) => send_response(response_out, 200, asset.mime, asset.data),
            None => send_response(
                response_out,
                404,
                "text/plain",
                b"404 Not Found",
            ),
        }
    }
}

fn send_response(response_out: ResponseOutparam, status: u16, mime: &str, body: &[u8]) {
    let headers = Fields::new();
    headers
        .set("content-type", &[mime.as_bytes().to_vec()])
        .unwrap_or(());
    headers
        .set(
            "content-length",
            &[body.len().to_string().into_bytes()],
        )
        .unwrap_or(());

    let resp = OutgoingResponse::new(headers);
    resp.set_status_code(status).unwrap_or(());

    // Acquire the body handle before committing the response.
    let ob: OutgoingBody = resp.body().unwrap();

    // Commit the response headers so the client receives them and the
    // runtime can begin streaming the body downstream.
    ResponseOutparam::set(response_out, Ok(resp));

    let stream = ob.write().unwrap();

    // Write the body in bounded chunks (blocking-write-and-flush caps at
    // 4096 bytes per call in the wasi:io/streams spec).
    for chunk in body.chunks(CHUNK) {
        stream.blocking_write_and_flush(chunk).unwrap_or(());
    }

    // Drop the stream before finishing the body — required by the spec.
    drop(stream);
    OutgoingBody::finish(ob, None).unwrap_or(());
}

wasi::http::proxy::export!(Handler);