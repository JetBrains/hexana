package org.jetbrains.hexana.ui.panel

import java.io.File

/**
 * Standalone entry point that generates the `hexana-scripting.d.ts` file
 * using [BinaryProviderApiSummary] and writes it to one or more output
 * directories supplied as command-line arguments.
 *
 * Usage: `java … GenerateScriptingDtsKt <outputDir1> [<outputDir2> …]`
 *
 * Each output directory will receive a `hexana-scripting.d.ts` file.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "At least one output directory must be specified." }
    val dts = BinaryProviderApiSummary.generateDts()
    for (dir in args) {
        val outDir = File(dir)
        outDir.mkdirs()
        val outFile = File(outDir, "hexana-scripting.d.ts")
        outFile.writeText(dts)
        println("Wrote ${outFile.absolutePath}")
    }
}
