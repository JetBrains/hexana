package org.jetbrains.hexana.graalwasm
import org.graalvm.polyglot.Context

fun initWasmContext(): Context = Context.newBuilder("wasm")
    .option("engine.WarnInterpreterOnly", "false")
    .build() ?: throw Exception("Failed to initialize context")
