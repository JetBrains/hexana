package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.ParseCoverage

/**
 * The notice every host shows (as the "Editing disabled" badge's tooltip) for a file it could
 * load only the head of (see `MAX_BINARY_WINDOW_BYTES` in `binaryProvider`): how much the hex
 * view shows, whether the tabs miss anything ([coverage]), and that editing is off —
 * overwriting the file from a partial view would truncate it.
 */
fun partialViewNotice(loadedBytes: Int, fileSize: Long, coverage: ParseCoverage): String =
    "Showing the first ${formatSizeLong(loadedBytes.toLong())} of this ${formatSizeLong(fileSize)} file. " +
        "${coverageSentence(coverage)} Editing is turned off."

/**
 * The notice for a host whose hex view, search and Go to Offset read the whole file while the
 * tabs come from what the parser read, which [coverage] says is all of it or a prefix.
 */
fun largeFileNotice(fileSize: Long, coverage: ParseCoverage): String {
    val shown = if (coverage is ParseCoverage.Prefix) "shown in full" else "shown and parsed in full"
    val tabs = if (coverage is ParseCoverage.Prefix) " ${coverageSentence(coverage)}" else ""
    return "This ${formatSizeLong(fileSize)} file is $shown.$tabs Editing is turned off for files this large."
}

/** "4.8 GB", marked "(first 2.0 GB parsed)" only when the tabs miss what follows. */
fun largeFileSizeLabel(fileSize: Long, coverage: ParseCoverage): String = when (coverage) {
    ParseCoverage.WholeFile -> formatSizeLong(fileSize)
    is ParseCoverage.Prefix -> "${formatSizeLong(fileSize)} (first ${formatSizeLong(coverage.parsedBytes)} parsed)"
}

private fun coverageSentence(coverage: ParseCoverage): String = when (coverage) {
    ParseCoverage.WholeFile -> "The tabs list every structure."
    is ParseCoverage.Prefix -> "Tabs leave out ${coverage.missing} past the first ${formatSizeLong(coverage.parsedBytes)}."
}

/** Whether a host that loaded [loadedBytes] of a [fileSize]-byte file is showing only part of it. */
fun isPartialView(loadedBytes: Int, fileSize: Long?): Boolean = fileSize != null && fileSize > loadedBytes
