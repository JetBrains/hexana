package org.jetbrains.hexana.wasm.sections

/**
 * Describes a region of the WASM binary that could not be fully parsed.
 *
 * @property kind Classification of what went wrong.
 * @property sectionId Raw WASM section ID (1 = Type, 10 = Code, etc.).
 * @property sectionName Human-readable section name (e.g. "Types", "Code").
 * @property funcIdx Global function index (imports first, then defined functions), or null
 *   when the problem is not associated with a specific function.
 * @property typeIdx Type-section index of the affected entry, or null when not applicable.
 * @property byteOffset Absolute byte offset of the failed region in the binary.
 * @property byteSize Byte length of the failed region.
 * @property message Short description of what went wrong, suitable for display.
 */
data class ParseProblem(
    val kind: ParseProblemKind,
    val sectionId: Int,
    val sectionName: String,
    val funcIdx: Int?,
    val typeIdx: Int?,
    val byteOffset: Int,
    val byteSize: Int,
    val message: String,
)

/** Classification of parse failures recorded in [ParseProblem]. */
enum class ParseProblemKind {
    /** Local variable declarations in a function body could not be decoded. */
    LOCALS_PARSE_FAILURE,
    /** An unknown opcode was encountered; the opcode tail is preserved as [UnparsedOpCode]. */
    UNKNOWN_OPCODE,
    /** The entire function body (including locals) could not be decoded. */
    CODE_ENTRY_CORRUPT,
    /** An unknown byte was encountered while decoding a type-section entry. */
    UNKNOWN_TYPE_BYTE,
    /** An init-expression (global/element/data) could not be parsed. */
    INIT_EXPR_PARSE_FAILURE,
    /** A component core-instance entry starts with a tag byte other than 0x00/0x01. */
    UNKNOWN_CORE_INSTANCE_TAG,
    /** A component core-instance entry failed to decode; the section remainder is skipped. */
    CORE_INSTANCE_ENTRY_CORRUPT,
    /** A section header or custom-section name could not be read (truncated binary). */
    SECTION_TRUNCATED,
}