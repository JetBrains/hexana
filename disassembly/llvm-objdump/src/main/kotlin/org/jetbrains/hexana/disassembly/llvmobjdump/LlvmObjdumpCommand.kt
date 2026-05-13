package org.jetbrains.hexana.disassembly.llvmobjdump

import java.nio.file.Path

enum class LlvmObjdumpCommandMode {
    Headers,
    Symbols,
    Relocations,
    DynamicRelocations,
    DisassembleExecutableSections,
    DisassembleSymbol,
}

data class LlvmObjdumpCommand(
    val mode: LlvmObjdumpCommandMode,
    val arguments: List<String>,
)

enum class LlvmObjdumpSyntaxMode(val label: String, val argument: String?) {
    ArchitectureDefault("Default", null),
    Intel("Intel", "--x86-asm-syntax=intel"),
    Att("AT&T", "--x86-asm-syntax=att"),
}

object LlvmObjdumpCommands {
    fun headers(file: Path): LlvmObjdumpCommand = command(
        mode = LlvmObjdumpCommandMode.Headers,
        "--file-headers",
        "--section-headers",
        file.pathArgument(),
    )

    fun symbols(file: Path): LlvmObjdumpCommand = command(
        mode = LlvmObjdumpCommandMode.Symbols,
        "--syms",
        file.pathArgument(),
    )

    fun relocations(file: Path): LlvmObjdumpCommand = command(
        mode = LlvmObjdumpCommandMode.Relocations,
        "--reloc",
        file.pathArgument(),
    )

    fun dynamicRelocations(file: Path): LlvmObjdumpCommand = command(
        mode = LlvmObjdumpCommandMode.DynamicRelocations,
        "--dynamic-reloc",
        file.pathArgument(),
    )

    fun disassembleExecutableSections(
        file: Path,
        syntaxMode: LlvmObjdumpSyntaxMode = LlvmObjdumpSyntaxMode.ArchitectureDefault,
    ): LlvmObjdumpCommand = command(
        mode = LlvmObjdumpCommandMode.DisassembleExecutableSections,
        arguments = listOfNotNull(
            "--disassemble",
            syntaxMode.argument,
            file.pathArgument(),
        ),
    )

    fun disassembleSymbol(
        file: Path,
        symbol: String,
        syntaxMode: LlvmObjdumpSyntaxMode = LlvmObjdumpSyntaxMode.ArchitectureDefault,
    ): LlvmObjdumpCommand {
        require(symbol.isNotBlank()) { "symbol must not be blank" }
        return command(
            mode = LlvmObjdumpCommandMode.DisassembleSymbol,
            arguments = listOfNotNull(
                "--disassemble",
                syntaxMode.argument,
                "--disassemble-symbols=$symbol",
                file.pathArgument(),
            ),
        )
    }

    private fun command(
        mode: LlvmObjdumpCommandMode,
        vararg arguments: String,
    ): LlvmObjdumpCommand = LlvmObjdumpCommand(mode, arguments.toList())

    private fun command(
        mode: LlvmObjdumpCommandMode,
        arguments: List<String>,
    ): LlvmObjdumpCommand = LlvmObjdumpCommand(mode, arguments)
}

private fun Path.pathArgument(): String = toString()
