const asm = await WebAssembly.instantiateStreaming(
    fetch('./simple.wasm'),
    { my_namespace: { imported_func: function(p) {} } }
);
