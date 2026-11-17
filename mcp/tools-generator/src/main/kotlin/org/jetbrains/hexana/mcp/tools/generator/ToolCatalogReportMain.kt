package org.jetbrains.hexana.mcp.tools.generator

import org.jetbrains.hexana.mcp.tools.ResolvedToolCatalogReport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val outputDirectory = args.singleOrNull()?.let(Path::of) ?: run {
        System.err.println("Usage: tool-catalog-report <output-directory>")
        exitProcess(1)
    }
    Files.createDirectories(outputDirectory)
    val report = ResolvedToolCatalogReport()
    Files.writeString(outputDirectory.resolve("resolved-tool-catalog.json"), report.json())
    Files.writeString(outputDirectory.resolve("resolved-tool-catalog.txt"), report.text())
    println("Wrote resolved tool catalog report to $outputDirectory")
}
