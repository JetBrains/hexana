package org.jetbrains.hexana.shared

import org.jetbrains.hexana.pcap.PcapDissectedPacket
import org.jetbrains.hexana.pcap.PcapDissector
import org.jetbrains.hexana.pcap.PcapFile
import org.jetbrains.hexana.pcap.TlsDecryptionContext
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.PcapBinaryProvider

class PcapTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is PcapBinaryProvider) return emptyList()
        val pcap = provider.parsedPcapFile ?: return emptyList()
        return listOfNotNull(
            pcapHeaderTab(pcap, provider),
            pcapPacketsTab(pcap, provider),
        )
    }

    /**
     * Both tab names are known from the provider type alone, without parsing.
     * Answering here keeps hosts from calling [tableTabs] just to learn the
     * names — for a capture that means the parse, the TLS handshake scan and
     * the key derivation all stay on whatever background thread resolves the
     * tab, instead of running during composition.
     */
    override fun availableTabNames(
        provider: BinaryProvider,
        context: TableTabsContext,
    ): Pair<List<String>, List<String>>? =
        if (provider is PcapBinaryProvider) listOf("Header", "Packets") to emptyList() else null
}

private fun pcapHeaderTab(pcap: PcapFile, provider: PcapBinaryProvider): TableTabDescriptor {
    val h = pcap.header
    val isPcapNg = provider.isPcapNg
    val ngFile = provider.parsedPcapNgFile

    val rows = mutableListOf<Pair<String, String>>()
    rows += "Format" to if (isPcapNg) "PCAP-NG" else "PCAP"
    rows += "Version" to h.version
    if (!isPcapNg) {
        rows += "Byte order" to if (h.bigEndian) "Big-endian" else "Little-endian"
    }
    if (h.snapLen > 0) {
        rows += "Snap length" to "${h.snapLen}"
    }
    rows += "Link-layer type" to h.networkName
    if (ngFile != null && ngFile.interfaces.size > 1) {
        rows += "Interfaces" to "${ngFile.interfaces.size}"
    }
    rows += "Packets" to "${pcap.packets.size}"

    return TableTabDescriptor(
        name = "Header",
        columns = listOf(
            col("Property", ColumnWidth.MeasuredMin("Link-layer type")) { CellValue(rows[it].first) },
            col("Value", ColumnWidth.Fill()) { CellValue(rows[it].second) },
        ),
        rowCount = rows.size,
        showIdColumn = false,
        priority = TabPriority.High,
        prologueText = if (isPcapNg) "PCAP-NG Capture File" else "PCAP Capture File",
    )
}

private const val PACKET_HEADER_SIZE = 16

/** Formats a relative timestamp as seconds with 6 or 9 decimal places (matching Wireshark). */
private fun formatRelativeTime(seconds: Double, nsResolution: Boolean): String {
    val decimals = if (nsResolution) 9 else 6
    // Manual formatting to avoid platform-specific locale issues with Double.toString
    val negative = seconds < 0
    val abs = if (negative) -seconds else seconds
    val wholePart = abs.toLong()
    val fracPart = ((abs - wholePart) * (if (nsResolution) 1_000_000_000L else 1_000_000L) + 0.5).toLong()
    val prefix = if (negative) "-" else ""
    return "$prefix$wholePart.${fracPart.toString().padStart(decimals, '0')}"
}

private fun pcapPacketsTab(
    pcap: PcapFile,
    provider: PcapBinaryProvider,
): TableTabDescriptor? {
    val packets = pcap.packets
    if (packets.isEmpty()) return null

    val nsResolution = pcap.header.magic == org.jetbrains.hexana.pcap.PCAP_MAGIC_NSEC_LE ||
        pcap.header.magic == org.jetbrains.hexana.pcap.PCAP_MAGIC_NSEC_BE

    val linkType = pcap.header.network
    val hasKeyLog = provider.sslKeyLog.isNotEmpty

    // Build a decryption context whenever a key log is loaded; which suites it
    // can actually open is the platform's business, not this tab's.
    val decryptionContext: TlsDecryptionContext? = if (hasKeyLog) {
        try {
            TlsDecryptionContext.build(
                buffer = provider.bytes,
                packets = packets,
                linkType = linkType,
                sslKeyLog = provider.sslKeyLog,
                tlsSessionMap = provider.tlsSessionMap,
            )
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }

    // Reference timestamp from the first packet for relative display (like Wireshark)
    val firstPacket = packets[0]
    val refSec = firstPacket.tsSec
    val refUsec = firstPacket.tsUsec
    val fracDivisor = if (nsResolution) 1_000_000_000.0 else 1_000_000.0

    // Lazily dissect packets on first access per row
    val dissectedCache = HashMap<Int, PcapDissectedPacket>()
    fun dissected(index: Int): PcapDissectedPacket = dissectedCache.getOrPut(index) {
        try {
            PcapDissector.dissect(provider.bytes, packets[index], linkType, decryptionContext)
        } catch (_: Exception) {
            PcapDissectedPacket("", "", "?", "")
        }
    }

    val columns = mutableListOf(
        col("No.", ColumnWidth.MeasuredMin("99999")) { CellValue(it + 1) },
        col("Time", ColumnWidth.MeasuredMin("00000.000000")) { i ->
            val p = packets[i]
            val deltaSec = (p.tsSec - refSec).toDouble() + (p.tsUsec - refUsec).toDouble() / fracDivisor
            val formatted = formatRelativeTime(deltaSec, nsResolution)
            CellValue(formatted)
        },
        col("Source", ColumnWidth.MeasuredMin("000.000.000.000")) { CellValue(dissected(it).source) },
        col("Destination", ColumnWidth.MeasuredMin("000.000.000.000")) { CellValue(dissected(it).destination) },
        col("Protocol", ColumnWidth.MeasuredMin("Protocol")) { CellValue(dissected(it).protocol) },
        col("Length", ColumnWidth.MeasuredMin("Length")) { CellValue(packets[it].origLen) },
        col("Info", ColumnWidth.Fill()) { CellValue(dissected(it).info) },
    )

    // When an SSL key log is loaded, add a column showing decryption status
    if (hasKeyLog) {
        columns.add(col("Decrypted", ColumnWidth.MeasuredMin("Decrypted")) { i ->
            val d = dissected(i)
            val decrypted = d.decryptedData
            val text = when {
                decrypted != null -> "✓ ${decrypted.size} bytes"
                // Keys were available; the platform's decryption budget ran
                // out before this record. Distinguished from "encrypted" so
                // the row does not read as missing key material.
                decryptionContext?.isBeyondDecryptionBudget(packets[i]) == true -> "🔒 not decrypted"
                d.protocol.startsWith("TLS") && d.info.contains("Application Data") -> "🔒 encrypted"
                else -> ""
            }
            CellValue(text)
        })
    }

    // Reuses the dissection cache above, so opening the panel costs no extra
    // decryption. Without a key log there is no plaintext to show at all.
    val rowDetailProvider: ((Int) -> RowDetailData?)? =
        if (decryptionContext == null) null else ({ i: Int -> decryptedRowDetail(dissected(i), i) })

    val prologueText = if (hasKeyLog) {
        decryptionPrologue(provider.sslKeyLog.sessionCount, decryptionContext)
    } else {
        null
    }

    return TableTabDescriptor(
        name = "Packets",
        columns = columns,
        rowCount = packets.size,
        defaultSortColumn = 0,
        alwaysShowSearch = true,
        showIdColumn = false,
        searchPlaceholder = "Search packets",
        priority = TabPriority.High,
        prologueText = prologueText,
        rowSelectionProvider = { i ->
            val p = packets[i]
            val offset = p.fileOffset.toInt()
            val size = (PACKET_HEADER_SIZE + p.inclLen).toInt()
            RowSelectionData(offset, size)
        },
        rowDetailProvider = rowDetailProvider,
    )
}

/**
 * The plaintext of a record that opened, labelled for the hex view's side
 * panel. Returns `null` for a row that did not decrypt - the panel then says
 * so rather than showing the previous row's bytes.
 */
internal fun decryptedRowDetail(packet: PcapDissectedPacket, index: Int): RowDetailData? {
    val bytes = packet.decryptedData ?: return null
    return RowDetailData(
        title = "Decrypted TLS",
        bytes = bytes,
        subtitle = "packet #${index + 1} · ${packet.protocol} · ${bytes.size} bytes",
    )
}

/**
 * The Packets tab header once a key log is loaded. Everything after the
 * session count answers the one question a locked row raises: *why*. A capture
 * that decrypts nothing otherwise looks exactly like one that decrypts
 * everything, which is how a cipher suite this build has no entry for reads as
 * a broken key log.
 */
private fun decryptionPrologue(sessionCount: Int, context: TlsDecryptionContext?): String {
    val parts = mutableListOf("🔑 SSL key log loaded ($sessionCount sessions)")

    // Name the suites this platform cannot open, so rows that stay locked read
    // as a known limitation rather than a failure to find the keys.
    val undecryptable = context?.undecryptableCipherSuites.orEmpty()
    if (undecryptable.isNotEmpty()) {
        parts += undecryptable.joinToString(", ") { it.displayName } +
            " cannot be decrypted on this platform"
    }

    val diagnostics = context?.streamDiagnostics
    if (diagnostics != null && diagnostics.hasFailures) {
        val reasons = mutableListOf<String>()
        if (diagnostics.unsupportedCipherSuiteIds.isNotEmpty()) {
            val ids = diagnostics.unsupportedCipherSuiteIds.sorted()
                .joinToString(", ") { "0x" + it.toString(16).padStart(4, '0') }
            reasons += "cipher suite $ids is not supported"
        }
        if (diagnostics.streamsWithoutKeys > 0) {
            reasons += "${diagnostics.streamsWithoutKeys} sessions are not in the key log"
        }
        if (diagnostics.keyDerivationFailures > 0) {
            reasons += "${diagnostics.keyDerivationFailures} sessions had unusable key material"
        }
        if (diagnostics.compressionFailures > 0) {
            reasons += "${diagnostics.compressionFailures} sessions use a compression method " +
                "this build cannot undo"
        }
        if (diagnostics.unauthenticatedStreams > 0) {
            reasons += "${diagnostics.unauthenticatedStreams} sessions did not authenticate " +
                "(the key log may be from a different run)"
        }
        parts += "${diagnostics.decryptableStreams} sessions decrypt; " + reasons.joinToString("; ")
    }

    // Say so when the platform's decryption budget clipped the capture, rather
    // than leaving a stretch of locked rows unexplained.
    val skipped = context?.recordsBeyondBudgetCount ?: 0
    if (skipped > 0) {
        val total = context?.decryptableRecordCount ?: 0
        parts += "decrypted the first ${total - skipped} of $total records; " +
            "the rest were skipped to keep this platform responsive"
    }

    return parts.joinToString(" — ")
}
