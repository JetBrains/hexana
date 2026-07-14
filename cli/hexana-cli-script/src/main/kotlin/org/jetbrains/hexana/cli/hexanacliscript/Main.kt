package org.jetbrains.hexana.cli.hexanacliscript

import org.jetbrains.hexana.cli.product.HexanaCliProduct
import org.jetbrains.hexana.cli.product.runHexanaProductCli

fun main(args: Array<String>) {
    if (System.getProperty("hexana.version").isNullOrBlank()) {
        System.setProperty("hexana.version", HEXANA_CLI_SCRIPT_VERSION)
    }
    runHexanaProductCli(HexanaCliProduct.SCRIPT, args)
}
