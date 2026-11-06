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

/** Whether a host that loaded [loadedBytes] of a [fileSize]-byte file is showing only part of it. */
fun isPartialView(loadedBytes: Int, fileSize: Long?): Boolean = fileSize != null && fileSize > loadedBytes
