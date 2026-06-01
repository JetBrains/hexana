package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.SectionKind

/**
 * [SectionKind] constants for WebAssembly. The string IDs are namespaced under
 * `wasm.*` so future formats (`elf.*`, `pe.*`, `jvm.*`, `llvm.*`) can coexist
 * without collision.
 *
 * The `HEADER` kind covers the 8-byte module header (`\0asm` + version) which
 * is not a section in the spec but is rendered as one for navigation.
 */
public object WasmSectionKinds {
    public val HEADER: SectionKind = SectionKind("wasm.header")
    public val TYPE: SectionKind = SectionKind("wasm.type")

    /**
     * Synthetic kind for the header row of a WasmGC `(rec …)` recursive type
     * group inside the Type section. Not a real section; used by the chunk
     * tree to mark a collapsible sub-group whose member [TYPE] chunks carry
     * its [org.jetbrains.hexana.document.format.BinaryChunk.Element.groupId].
     */
    public val TYPE_REC: SectionKind = SectionKind("wasm.type.rec")
    public val IMPORT: SectionKind = SectionKind("wasm.import")
    public val FUNCTION: SectionKind = SectionKind("wasm.function")
    public val TABLE: SectionKind = SectionKind("wasm.table")
    public val MEMORY: SectionKind = SectionKind("wasm.memory")
    public val GLOBAL: SectionKind = SectionKind("wasm.global")
    public val EXPORT: SectionKind = SectionKind("wasm.export")
    public val START: SectionKind = SectionKind("wasm.start")
    public val ELEMENT: SectionKind = SectionKind("wasm.element")
    public val DATA_COUNT: SectionKind = SectionKind("wasm.datacount")
    public val CODE: SectionKind = SectionKind("wasm.code")
    public val DATA: SectionKind = SectionKind("wasm.data")
    public val CUSTOM: SectionKind = SectionKind("wasm.custom")
    public val EXCEPTION: SectionKind = SectionKind("wasm.exception")
    public val UNKNOWN: SectionKind = SectionKind("wasm.unknown")

    /** Display name for the panel sticky header. */
    public fun displayName(kind: SectionKind): String = when (kind) {
        HEADER -> "header"
        TYPE -> "type"
        TYPE_REC -> "rec"
        IMPORT -> "import"
        FUNCTION -> "function"
        TABLE -> "table"
        MEMORY -> "memory"
        GLOBAL -> "global"
        EXPORT -> "export"
        START -> "start"
        ELEMENT -> "element"
        DATA_COUNT -> "datacount"
        CODE -> "code"
        DATA -> "data"
        CUSTOM -> "custom"
        EXCEPTION -> "exception"
        else -> kind.id
    }
}
