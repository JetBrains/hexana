package org.jetbrains.hexana.cli.hexanacli

import org.jetbrains.hexana.cli.product.HexanaCliProduct
import org.jetbrains.hexana.cli.product.runHexanaProductCli

fun main(args: Array<String>) {
    runHexanaProductCli(HexanaCliProduct.STANDARD, args)
}
