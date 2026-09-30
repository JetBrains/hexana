package org.jetbrains.hexana.cli.hexanawasmtools

import org.jetbrains.hexana.cli.metadata.HexanaGeneratedCliProducts
import org.jetbrains.hexana.cli.product.runHexanaProductCli

fun main(args: Array<String>) {
    if (System.getProperty("hexana.version").isNullOrBlank()) {
        System.setProperty("hexana.version", HEXANA_WASM_TOOLS_VERSION)
    }
    runHexanaProductCli(HexanaGeneratedCliProducts.HEXANA_WASM_TOOLS, args, HexanaWasmToolsExecutor)
}
