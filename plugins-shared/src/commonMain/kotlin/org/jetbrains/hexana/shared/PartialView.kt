package org.jetbrains.hexana.shared

/**
 * The notice every host shows above a file it could load only the head of (see
 * `MAX_BINARY_WINDOW_BYTES` in `binaryProvider`): how much is shown, what that means for the
 * tabs, and that editing is off — overwriting the file from a partial view would truncate it.
 */
fun partialViewNotice(loadedBytes: Int, fileSize: Long): String =
    "Showing the first ${formatSizeLong(loadedBytes.toLong())} of this ${formatSizeLong(fileSize)} file. " +
        "Structures stored after that point are not shown (model tensor tables are complete), " +
        "and editing is turned off."

/**
 * The notice for a host whose hex view, search and Go to Offset read the whole file while the
 * tabs still come from parsing only its first [parsedBytes].
 */
fun largeFileNotice(parsedBytes: Int, fileSize: Long): String =
    "This ${formatSizeLong(fileSize)} file is shown in full, but some tabs only list structures in its first " +
        "${formatSizeLong(parsedBytes.toLong())}. Editing is turned off for files this large."

/** Whether a host that loaded [loadedBytes] of a [fileSize]-byte file is showing only part of it. */
fun isPartialView(loadedBytes: Int, fileSize: Long?): Boolean = fileSize != null && fileSize > loadedBytes
