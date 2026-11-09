package org.jetbrains.hexana.providers

/**
 * How much of a file a [BinaryProvider]'s structures describe, from
 * [BinaryProvider.parseCoverage]. Hosts warn about a large file only when it is a [Prefix]:
 * a file larger than the parsers can read is often one small table plus large blobs that
 * the format just points at (model weights, an installer's overlay, archive members), and
 * then nothing is missing from the tabs.
 */
sealed interface ParseCoverage {
    /** Every structure the format places in the file was found; the bytes not read are blobs, or there are none. */
    data object WholeFile : ParseCoverage

    /**
     * Only the first [parsedBytes] were parsed, and structures may continue past them.
     * [missing] names what the tabs leave out, as a phrase that reads after "Tabs leave out":
     * "Wasm sections", "packets", "structures".
     */
    data class Prefix(val parsedBytes: Long, val missing: String = "structures") : ParseCoverage
}

/**
 * The coverage of a provider that parsed only its first [BinaryProvider.fileSize] bytes of a
 * [fileLength]-byte file and cannot tell whether more structures follow.
 */
fun BinaryProvider.prefixCoverage(fileLength: Long, missing: String = "structures"): ParseCoverage =
    if (fileSize >= fileLength) ParseCoverage.WholeFile else ParseCoverage.Prefix(fileSize, missing)

/**
 * [ParseCoverage.WholeFile] when every structure ends within the [BinaryProvider.fileSize] bytes
 * the provider parsed — [structuresEnd] is where the last one ends, or `null` when the headers
 * that would place them could not be read — else a [prefixCoverage].
 */
internal fun BinaryProvider.coverageByStructuresEnd(
    fileLength: Long,
    structuresEnd: Long?,
    missing: String = "structures",
): ParseCoverage =
    if (structuresEnd != null && structuresEnd <= fileSize) ParseCoverage.WholeFile else prefixCoverage(fileLength, missing)
