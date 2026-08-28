package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

/**
 * Returns `true` when [fileName] looks like a nested submodule opened in a
 * separate tab.  The naming convention `baseName_index@hexOffset.ext` is
 * produced by the modules tab — the `@` before the hex offset is the
 * distinguishing marker.
 */
internal fun isSubmoduleFileName(fileName: String): Boolean {
    val name = fileName.substringAfterLast('/').substringAfterLast('\\')
    return '_' in name && '@' in name
}

/**
 * Extracts the parent component file name from a submodule file name.
 *
 * Given the naming convention `baseName_index@hexOffset.ext`, this returns
 * `baseName.ext`.  Returns `null` when the file name does not look like a
 * submodule.
 */
internal fun parentFileNameOf(fileName: String): String? {
    val name = fileName.substringAfterLast('/').substringAfterLast('\\')
    if ('_' !in name || '@' !in name) return null
    val atIndex = name.indexOf('@')
    val underscoreIndex = name.lastIndexOf('_', atIndex)
    if (underscoreIndex < 0) return null
    val baseName = name.substring(0, underscoreIndex)
    val dotIndex = name.lastIndexOf('.')
    val extension = if (dotIndex >= 0) name.substring(dotIndex) else ".wasm"
    return "$baseName$extension"
}

/**
 * Builds the tab name for an embedded core module ([isComponent] false) or
 * nested component ([isComponent] true) of the component-model binary
 * [fileName], following the `baseName_index@hexOffset.ext` convention
 * [isSubmoduleFileName] and [parentFileNameOf] recognize.
 */
fun submoduleFileName(
    fileName: String,
    index: Int,
    contentOffset: Int,
    isComponent: Boolean,
): String {
    val name = fileName.substringAfterLast('/').substringAfterLast('\\')
    val baseName = name.substringBeforeLast('.')
    val dot = name.lastIndexOf('.')
    val extension = if (dot >= 0) name.substring(dot + 1) else "wasm"
    val infix = if (isComponent) "_component_" else "_"
    return "$baseName$infix$index@${contentOffset.toString(16)}.$extension"
}

/** Copies the content bytes of an embedded core module or nested component out of this component. */
internal fun WasmComponentBinaryProvider.submoduleBytes(contentOffset: Int, contentSize: Int): ByteArray {
    val target = ByteArray(contentSize)
    val buffer = bytes
    buffer.position(contentOffset)
    buffer.get(target, 0, contentSize)
    return target
}
