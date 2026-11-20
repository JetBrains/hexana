package org.jetbrains.hexana.shared

// Path arithmetic for files found next to a binary (PDBs, symbol maps). Works on both separators,
// since the VS Code webview hands over Windows paths too, and never touches the file system.

/** The last path segment. */
fun companionFileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

/** Everything before the last separator, keeping a root such as `/`. */
fun companionParentDirectory(path: String): String {
    val cut = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return if (cut <= 0) path.take(cut + 1) else path.substring(0, cut)
}

/** [name] inside [directory], with the separator [directory] already uses. */
fun companionJoin(directory: String, name: String): String = when {
    directory.isEmpty() -> name
    directory.endsWith('/') || directory.endsWith('\\') -> directory + name
    else -> "$directory${if (directory.contains('\\') && !directory.contains('/')) '\\' else '/'}$name"
}
