package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * The format a binary's bytes and name resolve to, as decided by
 * [resolveBinaryProviderKind].
 *
 * Exposed separately from [createBinaryProvider] for hosts that cannot use the
 * providers this module builds: the IntelliJ editor needs `VirtualFile`-backed
 * subclasses for the Wasm and generic cases (so the WAT side view can open a
 * document over the same file), but wants the format decision to be the shared
 * one.
 */
sealed interface BinaryProviderKind {
    /** Opaque Protobuf wire message, decoded without a schema. */
    object ProtobufMessage : BinaryProviderKind

    object Parquet : BinaryProviderKind

    data class Arrow(val format: ArrowFormat) : BinaryProviderKind

    object Wasm : BinaryProviderKind

    object WasmComponent : BinaryProviderKind

    /**
     * Everything [nativeOrGenericBinaryProvider] resolves from magic bytes alone:
     * ZIP, tar, `.class`, DEX, ARSC, pcap, protobuf descriptor sets, the native
     * formats, and the generic fallback.
     */
    object NativeOrGeneric : BinaryProviderKind
}

/**
 * Decides which provider [bytes] should be opened with.
 *
 * This is the single source of truth every host shares — the IntelliJ editor, the
 * VS Code webview, the browser webapp, and the script-tab `open(path)` path — so a
 * format the provider layer supports is supported everywhere rather than in
 * whichever host happened to add it.
 *
 * Content is asked first: Parquet, Arrow IPC files and WebAssembly all carry magic
 * strong enough to be believed over whatever the file happens to be called. Only when
 * none of them matches does [fileName] get a say, and only for the three formats no
 * magic identifies — a Protobuf wire message has none at all, and a Parquet or Arrow
 * file truncated past its trailing magic has lost its. Pass `null` when the bytes have
 * no name (an archive member, a payload pushed over a wire); resolution then rests on
 * content alone. Anything the name does not claim falls through to
 * [nativeOrGenericBinaryProvider], which is magic-only.
 *
 * gzip is deliberately not handled here: this function is synchronous (callers
 * invoke it from Compose `remember {}`) while the only common-code gunzip is
 * `suspend` and unimplemented on `wasmJs`, and each host has its own follow-up work
 * after inflating anyway. Hosts decompress first and pass the inflated bytes with
 * the `.gz` stripped from the name.
 */
fun resolveBinaryProviderKind(
    bytes: CommonByteBuffer,
    fileName: String? = null,
): BinaryProviderKind {
    // Content first. These three signatures are long and anchored, so a file carrying
    // one is that format whatever a build step named it.
    if (isParquetMagic(bytes.duplicate().position(0))) return BinaryProviderKind.Parquet
    if (isArrowFileMagic(bytes.duplicate().position(0))) return BinaryProviderKind.Arrow(ArrowFormat.FILE)
    when (WasmBinaryProviderSelector.select(bytes.duplicate().position(0))) {
        BinaryProviderType.Wasm -> return BinaryProviderKind.Wasm
        BinaryProviderType.WasmComponent -> return BinaryProviderKind.WasmComponent
        BinaryProviderType.Generic -> Unit
    }

    // Then the name, for the formats nothing above could have recognized.
    val ext = extensionOf(fileName) ?: return BinaryProviderKind.NativeOrGeneric
    return when (ext) {
        // A Protobuf wire message has no magic whatsoever, so the `.pb` name (or the
        // IDE's explicit "Open As Protobuf Message" action) is the only signal there is.
        "pb" -> BinaryProviderKind.ProtobufMessage
        "parquet" -> BinaryProviderKind.Parquet
        in ARROW_EXTENSIONS -> {
            // An Arrow stream's only marker is a 0xFFFFFFFF continuation word at offset
            // 0, far too weak to detect on its own — any file starting with four 0xFF
            // bytes would match. It counts only here, where the name has already said
            // Arrow and the much stronger file magic has already said "not a file".
            val format = if (isArrowStreamMagic(bytes.duplicate().position(0))) {
                ArrowFormat.STREAM
            } else {
                ArrowFormat.FILE
            }
            BinaryProviderKind.Arrow(format)
        }
        else -> BinaryProviderKind.NativeOrGeneric
    }
}

/**
 * Builds the provider [resolveBinaryProviderKind] resolves [bytes] and [fileName] to.
 *
 * [sourcePath] is the path the Parquet, Arrow, pcap and native providers record for
 * themselves — they surface it to scripts as `binary.path` and use it to reach
 * sibling files such as a `.dSYM` bundle or a `.map` sidecar. [fileName] is the name
 * the extension is read from, and defaults to [sourcePath] because usually they are
 * the same string. Pass them separately when the two disagree: a host that inflated
 * `foo.pb.gz` holds bytes named `foo.pb` while the path on disk still ends `.gz`.
 */
fun createBinaryProvider(
    bytes: CommonByteBuffer,
    sourcePath: String? = null,
    fileName: String? = sourcePath,
): BinaryProvider = createBinaryProvider(resolveBinaryProviderKind(bytes, fileName), bytes, sourcePath)

/**
 * Builds the provider for an already-decided [kind].
 *
 * For hosts that resolve the kind themselves, or adjust the one
 * [resolveBinaryProviderKind] returned: the IntelliJ editor also has the IDE's file
 * type registry to consult, which the shared resolution cannot see.
 */
fun createBinaryProvider(
    kind: BinaryProviderKind,
    bytes: CommonByteBuffer,
    sourcePath: String? = null,
): BinaryProvider {
    // Every provider below reads through `sharedBytes.duplicate()`, and a duplicate
    // inherits the source cursor — so a buffer handed over mid-read would have each
    // provider parsing from the middle of the file. Normalizing once here means
    // callers do not each have to remember to rewind.
    val shared = bytes.duplicate().position(0)
    return when (kind) {
        BinaryProviderKind.ProtobufMessage -> ProtobufMessageBinaryProvider(shared)
        BinaryProviderKind.Parquet -> ParquetBinaryProvider(shared, sourcePath)
        is BinaryProviderKind.Arrow -> ArrowBinaryProvider(shared, kind.format, sourcePath)
        BinaryProviderKind.Wasm -> WasmBinaryProvider(shared)
        BinaryProviderKind.WasmComponent -> WasmComponentBinaryProvider(shared)
        BinaryProviderKind.NativeOrGeneric -> nativeOrGenericBinaryProvider(shared, sourcePath)
    }
}

/** Extensions Arrow IPC files and streams are conventionally given. */
private val ARROW_EXTENSIONS = setOf("arrow", "arrows")

/**
 * Lowercased extension of [fileName]'s last path segment, or `null` when it has none.
 *
 * The segment is taken first so that a directory with a dot in its name
 * (`/tmp/v1.2/capture`) does not lend its suffix to an extensionless file. Both
 * separators are honoured because callers pass native paths from every platform.
 */
private fun extensionOf(fileName: String?): String? {
    if (fileName.isNullOrEmpty()) return null
    val name = fileName.substringAfterLast('/').substringAfterLast('\\')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return null
    return name.substring(dot + 1).lowercase()
}
