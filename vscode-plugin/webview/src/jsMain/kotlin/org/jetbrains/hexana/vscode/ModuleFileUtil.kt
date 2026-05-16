package org.jetbrains.hexana.vscode

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
