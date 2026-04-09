package org.jetbrains.hexana.wasm.analysis

enum class OpcodeCategory {
    Control,
    Call,
    Memory,
    Arithmetic,
    GC,
    SIMD,
    Other
}

enum class PartKind { Locals, Body }

enum class ContentKind { StringLiteral, Binary, ZeroFilled }