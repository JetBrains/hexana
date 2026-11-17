fetch("simple.wasm").then(response =>
    response.arrayBuffer()
).then(bytes =>
    WebAssembly.instantiate(bytes, { my_namespace: { imported_func: function(p) {} } })
).then(result => {
    result.instance.exports.exported_func();
});
