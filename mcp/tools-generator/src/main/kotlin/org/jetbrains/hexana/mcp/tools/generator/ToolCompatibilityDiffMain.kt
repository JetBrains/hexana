package org.jetbrains.hexana.mcp.tools.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.hexana.mcp.tools.ToolCompatibilityDiff
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size != 3) {
        System.err.println("Usage: tool-compatibility-diff <before-report.json> <after-report.json> <output-directory>")
        exitProcess(1)
    }
    val before = Json.parseToJsonElement(Files.readString(Path.of(args[0]))).jsonObject
    val after = Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
    val outputDirectory = Path.of(args[2])
    Files.createDirectories(outputDirectory)
    val diff = ToolCompatibilityDiff.between(before, after)
    Files.writeString(outputDirectory.resolve("tool-compatibility-diff.json"), diff.json())
    Files.writeString(outputDirectory.resolve("tool-compatibility-diff.txt"), diff.text())
    println("Wrote tool compatibility diff to $outputDirectory")
}
