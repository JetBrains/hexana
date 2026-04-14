WebAssembly.compileStreaming(fetch("simple.wasm"))
    .then(module => WebAssembly.instantiate(module, importObject))
    .then(instance => {
        instance.exports.exported_func();
    });