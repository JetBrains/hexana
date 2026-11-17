const asm = await WebAssembly.instantiateStreaming(
    fetch('./calculator.wasm'),
    {}
);

asm.instance.exports.calculate()
