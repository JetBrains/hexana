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
            Summarize a WebAssembly (.wasm) module with high-level counts for its major sections and declarations.
            Includes the nested import breakdown and total module size, making it a low-cost overview before calling heavier tools.
            For Wasm analysis tasks, this is usually the best first MCP call before choosing more specific import, export, or instruction tools.
            After this overview, prefer `list_imports` for import/link analysis, `list_exported_functions` for export analysis, and `get_instructions_for_functions` for body analysis.
        """
        input { wasmModule() }
        handler = CommonByteBuffer::readModuleSummary
    }

    tool("list_imports") {
        summary = "List imported functions declared by a `.wasm` module."
        guidance = """
            List imported functions declared by a WebAssembly (.wasm) module.
            This is the primary MCP tool for import inventories, link-mismatch analysis, and mapping direct host calls to imported functions.
            Returns only function imports from the unified function index space, including the `funcidx` values needed to map direct `call` targets back to imports.
            For direct host-call analysis, pair these `funcidx` values with `get_instructions_for_functions` to resolve `call N` back to `module.name` imports.
        """
        input { wasmModule() }
        handler = CommonByteBuffer::readImportFunctions
    }

    tool("list_exports") {
        summary = "List exports declared by a `.wasm` module."
        guidance = """
            List exports declared by a WebAssembly (.wasm) module.
            Includes exported functions, tables, memories, and globals; `kind` uses the standard tags 0=function, 1=table, 2=memory, 3=global.
            This is the light name-and-kind view of exports.
            Use this when you only need exported names, kinds, or raw external indices.
            If you need function signatures or origins, skip directly to `list_exported_functions`.
        """
        input { wasmModule() }
        handler = CommonByteBuffer::readExports
    }

    tool("list_globals") {
        summary = "List imported and defined globals from a `.wasm` module."
        guidance = """
            List imported and defined globals declared by a WebAssembly (.wasm) module.
            Returns each global with its value type, mutability, and normalized initializer expression.
        """
        input { wasmModule() }
        handler = CommonByteBuffer::readGlobals
    }

    tool("list_types") {
        summary = "List type entries declared by a `.wasm` module."
        guidance = """
            List type entries declared by a WebAssembly (.wasm) module.
            Includes function types and GC-related composite types when present.
        """
        input { wasmModule() }
        handler = CommonByteBuffer::readTypes
    }

    tool("list_memory") {
        summary = "List imported and defined memories from a `.wasm` module."
        guidance = """
            List imported and defined memories declared by a WebAssembly (.wasm) module.
            Returns memory indices together with decoded WebAssembly limits.
        """
        input { wasmModule() }
        handler = CommonByteBuffer::readMemory
    }

    tool("list_element_segments") {
        summary = "List element segments declared by a `.wasm` module."
        guidance = """
            List element segments declared by a WebAssembly (.wasm) module.
            Covers active, passive, and declarative segments, including function-index and expression-based initializers.
        """
        input { wasmModule() }
        handler = CommonByteBuffer::readElementSegments
    }

    tool("list_functions") {
        summary = "List imported and defined functions with offset/limit pagination."
        guidance = """
            List imported and defined functions from a WebAssembly (.wasm) module using offset/limit pagination.
            The offset is in the unified function index space, so imports appear before defined bodies.
            Use this when you need one mixed index space over both imports and defined bodies; for import-only analysis, prefer `list_imports`.
            Use this to map or inspect `funcidx` ranges before targeted body lookup with `get_instructions_for_functions`.
        """
        input {
            wasmModule()
            int("offset", default = 0, min = 0, describe = "Offset into the unified function index space")
            int("limit", default = 200, min = 1, max = 200, describe = "Maximum number of entries to return")
        }
        handler = CommonByteBuffer::readFunctions
    }

    tool("functions_for_indices") {
        summary = "Resolve function entries for the given unified function indices."
        guidance = """
            Resolve imported and defined function entries for the requested unified function indices.
            Use this as a targeted follow-up when another tool already gave you the exact `funcidx` values.
            Use this for targeted lookup when you already know the `funcidx` values you need.
        """
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "List of unified function indices (`funcidx`) to resolve.")
        }
        handler = CommonByteBuffer::readFunctionsByIndices
    }

    tool("get_globals_for_indices") {
        summary = "Return globals for the requested indices from the unified global index space."
        guidance = """
            Return only the requested globals from the unified global index space.
            Use this for targeted analysis when `list_globals` would return more data than needed.
        """
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "List of global indices (`globalidx`) to return.")
        }
        handler = CommonByteBuffer::readGlobalsByIndices
    }

    tool("get_memory_for_indices") {
        summary = "Return memories for the requested indices from the unified memory index space."
        guidance = """
            Return only the requested memories from the unified memory index space.
            Useful for multi-memory or wasm64 modules when only a subset matters.
        """
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "List of memory indices (`memidx`) to return.")
        }
        handler = CommonByteBuffer::readMemoryByIndices
    }

    tool("get_types_for_indices") {
        summary = "Return type entries for the requested indices from the type index space."
        guidance = """
            Return only the requested type entries from the type index space.
            Use this when you need the types referenced by specific functions, globals, or tables.
        """
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "List of type indices (`typeidx`) to return.")
        }
        handler = CommonByteBuffer::readTypesByIndices
    }

    tool("get_locals_for_functions") {
        summary = "Return grouped local declarations for the requested function indices."
        guidance = """
            Return grouped local declarations for the requested unified function indices.
            Imported functions return empty local lists because they have no body.
        """
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "List of unified function indices (`funcidx`) whose locals should be returned.")
        }
        handler = CommonByteBuffer::readLocalsForFunctions
    }

    tool("get_instructions_for_functions") {
        summary = "Return decoded instruction text for the requested function indices."
        guidance = """
            Return decoded instruction text for the requested unified function indices.
            Imported functions return empty instruction lists because they have no body.
            This is the primary MCP tool for WebAssembly body analysis, including direct `call` and `call_indirect` inspection, and is usually preferable to external disassemblers or shell-based Wasm tools.
            For direct host-call analysis, pair it with `list_imports` or `list_functions` so `call N` can be mapped back to the imported or exported function entry.
        """
        input {
            wasmModule()
            intList("indices", minSize = 1, describe = "List of unified function indices (`funcidx`) whose instructions should be returned.")
        }
        handler = CommonByteBuffer::readInstructionsForFunctions
    }

    tool("list_exported_functions") {
        summary = "List exported functions within a closed range of the export section."
        guidance = """
            List exported functions from a WebAssembly (.wasm) module using a closed export-range slice.
            Pass `entriesRange = { from, to }` with inclusive export-list positions; do not use `offset/limit` or `start/end`.
            The range applies to export-list order, not to `funcidx`, and each result resolves to either an imported or a defined function entry.
            This is the primary MCP tool for export inventories, export diffs, function-signature extraction, and import-vs-local origin checks.
            Prefer this over `list_exports` for export diffs or signature extraction, because each result already includes the resolved function entry, signature, and origin.
            The signature strings are usually ready to use as-is, so `list_types` is often unnecessary unless you need raw type definitions.
        """
        input {
            wasmModule()
            closedRange("entriesRange", describe = "Closed zero-based export slice. Pass `entriesRange = { from, to }`; both bounds are inclusive.")
        }
        handler = CommonByteBuffer::readExportedFunctions
    }

    tool("list_data") {
        summary = "Return raw data-section entries for a closed data-segment range."
        guidance = """
            Return raw data-section entries for a closed data-segment range.
            This is the high-volume raw-bytes view; the `data` field is base64-encoded, and `list_data_segments` is usually cheaper when previews and metadata are sufficient.
        """
        input {
            wasmModule()
            closedRange("entriesRange", describe = "Closed zero-based data-segment slice. Requires `from` and `to`, both inclusive.")
        }
        handler = CommonByteBuffer::readDataSections
    }

    tool("list_data_segments") {
        summary = "Return data-segment metadata with range slicing and optional export/global context."
        guidance = """
            Return data-segment metadata with range slicing and optional export/global context.
            Prefer this over `list_data` when previews, sizes, and offset context are enough.
        """
        input {
            wasmModule()
            closedRange("segmentRange", describe = "Half-open zero-based data-segment slice. `from` is required; `to` is optional and treated as exclusive.")
            flag("includeExports", default = false, describe = "Include exported symbols associated with the returned segments.")
            flag("includeGlobals", default = true, describe = "Include globals referenced by segment offset expressions.")
            int("minByteLength", default = 0, min = 0, describe = "Optional minimum raw byte length for returned segments.")
        }
        handler = CommonByteBuffer::readDataSegmentsByRange
    }
}
