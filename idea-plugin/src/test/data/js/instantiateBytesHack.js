fetch("simple.wasm").then(response =>
    response.arrayBuffer()
).then(bytes =>
    WebAssembly.instantiate(bytes, importObject)
).then(result => {
    result.instance.exports.exported_func();
});