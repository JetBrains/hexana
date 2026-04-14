WebAssembly.instantiateStreaming(fetch("add.wasm"))
    .then(obj => {
        console.log(obj.instance.exports.add(1, 2));  // "3"
    });