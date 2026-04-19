package org.jetbrains.hexana.mcp.tools

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.query.readDataSections
import org.jetbrains.hexana.mcp.query.readDataSegmentsByRange
import org.jetbrains.hexana.mcp.query.readElementSegments
import org.jetbrains.hexana.mcp.query.readExportedFunctions
import org.jetbrains.hexana.mcp.query.readExports
import org.jetbrains.hexana.mcp.query.readFunctions
import org.jetbrains.hexana.mcp.query.readFunctionsByIndices
import org.jetbrains.hexana.mcp.query.readGlobals
import org.jetbrains.hexana.mcp.query.readGlobalsByIndices
import org.jetbrains.hexana.mcp.query.readImportFunctions
import org.jetbrains.hexana.mcp.query.readInstructionsForFunctions
import org.jetbrains.hexana.mcp.query.readLocalsForFunctions
import org.jetbrains.hexana.mcp.query.readMemory
import org.jetbrains.hexana.mcp.query.readMemoryByIndices
import org.jetbrains.hexana.mcp.query.readModuleSummary
import org.jetbrains.hexana.mcp.query.readTypes
import org.jetbrains.hexana.mcp.query.readTypesByIndices

val HexanaTools: List<ToolSpec> = tools {

    tool("summarize_module") {
        summary = "Summarize a WebAssembly module — counts of imports, exports, memories, globals, functions, types."
        guidance = """
            Use first when the user hasn't asked for a narrower view.
            Drill down with `list_imports`, `list_exports`, `list_functions`, `list_globals`.
        """
        input { wasmModule() }
        handler = CommonByteBuffer::readModuleSummary
    }

    tool("list_imports") {
        summary = "List imported functions declared by a `.wasm` module."
        guidance = """
            Primary tool for import inventories and mapping direct host calls.
            For `call N` → `module.name` resolution, pair with `get_instructions_for_functions`.
        """
        input { wasmModule() }
        handler = CommonByteBuffer::readImportFunctions
    }

    tool("list_exports") {
        summary = "List exports declared by a `.wasm` module."
        input { wasmModule() }
        handler = CommonByteBuffer::readExports
    }

    tool("list_globals") {
        summary = "List imported and defined globals from a `.wasm` module."
        input { wasmModule() }
        handler = CommonByteBuffer::readGlobals
    }

    tool("list_types") {
        summary = "List type entries declared by a `.wasm` module."
        input { wasmModule() }
        handler = CommonByteBuffer::readTypes
    }

    tool("list_memory") {
        summary = "List imported and defined memories from a `.wasm` module."
        input { wasmModule() }
        handler = CommonByteBuffer::readMemory
    }

    tool("list_element_segments") {
        summary = "List element segments declared by a `.wasm` module."
        input { wasmModule() }
        handler = CommonByteBuffer::readElementSegments
    }

    tool("list_functions") {
        summary = "List imported and defined functions with offset/limit pagination."
        guidance = """
            Pagination is over the unified function index space; imports come first,
            defined bodies second. For import-only analysis prefer `list_imports`.
        """
        input {
            wasmModule()
            int("offset", default = 0, min = 0, describe = "Starting unified function index (imports come first).")
            int("limit", default = 50, min = 1, max = 200, describe = "Maximum entries to return.")
        }
        handler = CommonByteBuffer::readFunctions
    }

    tool("functions_for_indices") {
        summary = "Resolve function entries for the given unified function indices."
        guidance = "Use when you already have specific `funcidx` values from `list_exports` / `list_imports` / `list_functions`."
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "Unified function indices to resolve.")
        }
        handler = CommonByteBuffer::readFunctionsByIndices
    }

    tool("get_globals_for_indices") {
        summary = "Return globals for the requested indices from the unified global index space."
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "Unified global indices to resolve.")
        }
        handler = CommonByteBuffer::readGlobalsByIndices
    }

    tool("get_memory_for_indices") {
        summary = "Return memories for the requested indices from the unified memory index space."
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "Unified memory indices to resolve.")
        }
        handler = CommonByteBuffer::readMemoryByIndices
    }

    tool("get_types_for_indices") {
        summary = "Return type entries for the requested indices from the type index space."
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "Type indices to resolve.")
        }
        handler = CommonByteBuffer::readTypesByIndices
    }

    tool("get_locals_for_functions") {
        summary = "Return grouped local declarations for the requested function indices."
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "Function indices whose locals should be resolved.")
        }
        handler = CommonByteBuffer::readLocalsForFunctions
    }

    tool("get_instructions_for_functions") {
        summary = "Return decoded instruction text for the requested function indices."
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "Function indices whose bodies should be decoded.")
        }
        handler = CommonByteBuffer::readInstructionsForFunctions
    }

    tool("list_exported_functions") {
        summary = "List exported functions within a closed range of the export section."
        input {
            wasmModule()
            closedRange("exportIdx", describe = "Closed [from, to] range in export-declaration order.")
        }
        handler = CommonByteBuffer::readExportedFunctions
    }

    tool("list_data") {
        summary = "Return raw data-section entries for a closed range."
        input {
            wasmModule()
            closedRange("dataIdx", describe = "Closed [from, to] range in data-section order.")
        }
        handler = CommonByteBuffer::readDataSections
    }

    tool("list_data_segments") {
        summary = "Return data-segment metadata with range slicing and optional context."
        guidance = "Pair with `list_data` for raw-byte access to the same segments."
        input {
            wasmModule()
            closedRange("segment", describe = "Closed [from, to] range over data-segment indices.")
            flag("includeExports", default = false, describe = "Attach exporting globals/memories.")
            flag("includeGlobals", default = false, describe = "Attach contextual globals referenced by the segment.")
            int(
                "minByteLength",
                default = 0,
                min = 0,
                describe = "Skip segments whose byte length is below this threshold; 0 disables the filter.",
            )
        }
        handler = CommonByteBuffer::readDataSegmentsByRange
    }
}
