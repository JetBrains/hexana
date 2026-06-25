package org.jetbrains.hexana.mcp.tools

private const val BINARIES_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.binaries"

val BinaryModuleIdentityResultClass = class_("BinaryModuleIdentityResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    nullable("path", of = string()).describe("Caller-supplied path label, when the binary came from a path.")
    string("format").describe("Stable format identifier such as `wasm`, `elf`, `mach_o`, `pe`, `coff`, `archive`, or `generic`.")
    string("displayFormat").describe("Human-readable format label.")
    int("byteLength").describe("Binary size in bytes.")
    nullable("architecture", of = string()).describe("Architecture or machine label when known.")
    nullable("endian", of = string()).describe("Endian label when known.")
    nullable("wordSize", of = string()).describe("Word size label when known.")
}

val BinarySummaryCountsResultClass = class_("BinarySummaryCountsResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    nullable("functions", of = int()).describe("Function count when the format exposes functions.")
    nullable("types", of = int()).describe("Type count when the format exposes type entries.")
    nullable("memories", of = int()).describe("Memory declaration count when known.")
    nullable("globals", of = int()).describe("Global declaration count when known.")
    nullable("tables", of = int()).describe("Table declaration count when known.")
    nullable("symbols", of = int()).describe("Symbol count when symbol tables are available.")
    nullable("imports", of = int()).describe("Import count when the format exposes imports.")
    nullable("exports", of = int()).describe("Export count when the format exposes exports.")
    nullable("sections", of = int()).describe("Section count when the format exposes sections.")
    nullable("segments", of = int()).describe("Segment or segment-like count when the format exposes it.")
    nullable("loadCommands", of = int()).describe("Mach-O load command count when known.")
    nullable("architectures", of = int()).describe("Universal/fat binary architecture slice count when known.")
    nullable("coreModules", of = int()).describe("Embedded core module count for WebAssembly components when known.")
    nullable("nestedComponents", of = int()).describe("Nested component count for WebAssembly components when known.")
    nullable("relocations", of = int()).describe("Relocation count when known.")
    nullable("strings", of = int()).describe("Printable string count when scanned for native formats.")
    nullable("archiveMembers", of = int()).describe("Archive member count when the format is an archive.")
}

val BinaryWarningResultClass = class_("BinaryWarningResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    string("code").describe("Stable warning code.")
    string("message").describe("Short warning message.")
}

val BinarySummaryResultClass = class_("BinarySummaryResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    embed("module", of = BinaryModuleIdentityResultClass).describe("Format, path label, size, and architecture identity.")
    embed("counts", of = BinarySummaryCountsResultClass).describe("Compact format-specific entity counts.")
    list("warnings", of = BinaryWarningResultClass).describe("Warnings for partial or unsupported deeper parsing.")
}

internal val BinaryTools: List<ToolSpec> = tools {
    tool("summarize_binary") {
        summary = "Summarize a Wasm or native binary with compact identity and entity counts."
        guidance = """
            Use this first for an unknown binary before choosing a format-specific follow-up tool.
            Returns format, path label, size, known architecture/endian details, and bounded counts only; never raw bytes or unbounded lists.
            For WebAssembly core modules, `summarize_module` remains the Wasm-specific summary with its existing payload shape.
            Warnings indicate unsupported or partial deeper parsing while preserving the compact summary when possible.
        """
        input = BinaryInputClass
        inputPattern = ToolInputPattern.BinaryOnly
        output = BinarySummaryResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readBinarySummary"
    }
}
