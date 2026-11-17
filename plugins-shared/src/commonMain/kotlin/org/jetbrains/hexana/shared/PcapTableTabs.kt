package org.jetbrains.hexana.shared

import org.jetbrains.hexana.pcap.PcapDissectedPacket
import org.jetbrains.hexana.pcap.PcapDissector
import org.jetbrains.hexana.pcap.PcapFile
import org.jetbrains.hexana.pcap.PcapPacketRecord
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

    /**
     * Resolves one advertised tab. Without this the default implementation
     * builds *both* tabs to hand back one of them, so a host that resolves
     * each name on its own coroutine - as the IntelliJ editor does - would
     * parse the capture twice and, with a key log loaded, derive the TLS keys
     * and scan the handshakes twice, concurrently, over the one mutable buffer
     * the provider reads through.
     */
    override fun singleTab(name: String, provider: BinaryProvider, context: TableTabsContext): TableTabDescriptor? {
        if (provider !is PcapBinaryProvider) return null
        val pcap = provider.parsedPcapFile ?: return null
        return when (name) {
            "Header" -> pcapHeaderTab(pcap, provider)
            "Packets" -> pcapPacketsTab(pcap, provider)
            else -> null
        }
    }
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
    // Hosts also read this to reach a decrypted HTTP body for "Open HTTP Body",
    // since a record's plaintext is exactly what they need and building a
    // second decryption context would repeat the whole scan-and-derive pass.
    val rowDetailProvider: ((Int) -> RowDetailData?)? =
        if (decryptionContext == null) null else ({ i: Int -> decryptedRowDetail(dissected(i), i) })

    val protocolFilters = protocolFilters(provider, packets, linkType, decryptionContext != null) { i ->
        dissected(i).protocol
    }

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
        predefinedFilters = protocolFilters,
        // Nothing is hidden until the user picks a protocol.
        defaultFilterShowAll = true,
    )
}

/**
 * The protocol family a Protocol cell belongs to: every TLS version is one
 * entry, so the dropdown does not list "TLSv1.2" and "TLSv1.3" apart.
 */
internal fun pcapProtocolFamily(protocol: String): String = if (protocol.startsWith("TLS")) "TLS" else protocol

/**
 * One filter per protocol family present in the capture, for the dropdown
 * above the Packets table. Families come from [PcapDissector.protocolInfo],
 * which reads headers only, so listing them costs no info-string building and
 * no decryption.
 *
 * A row matches when either the family it travelled as or the family its
 * Protocol cell shows equals the filter's: a decrypted HTTPS record is both
 * TLS on the wire and HTTP in the table, and is listed under each. Since a
 * record only becomes HTTP once it opens, "HTTP" is offered whenever TLS is
 * present and a key log could open it.
 *
 * [displayedProtocol] decrypts TLS application data, so it is consulted for a
 * TLS row only by the HTTP filter, the one answer decryption can change; every
 * other filter decides a TLS row from its wire family. Other rows still check
 * the Protocol cell, which never decrypts them, because the header-only HTTP
 * test is looser than the table's (two bytes against a parsed request line).
 *
 * Returns no filters for a capture with a single family, where the dropdown
 * would have nothing to narrow.
 */
internal fun protocolFilters(
    provider: PcapBinaryProvider,
    packets: List<PcapPacketRecord>,
    linkType: Long,
    canDecrypt: Boolean,
    displayedProtocol: (Int) -> String,
): List<PredefinedFilter> {
    val wireFamilies = Array(packets.size) { i ->
        try {
            PcapDissector.protocolInfo(provider.bytes, packets[i], linkType)?.let(::pcapProtocolFamily)
        } catch (_: Exception) {
            null
        }
    }
    val families = wireFamilies.filterNotNullTo(LinkedHashSet())
    if (canDecrypt && "TLS" in families) families += "HTTP"
    if (families.size < 2) return emptyList()
    return families.sorted().map { family ->
        PredefinedFilter(family) { i ->
            val wire = wireFamilies.getOrNull(i)
            when {
                wire == family -> true
                wire == "TLS" -> canDecrypt && family == "HTTP" &&
                    pcapProtocolFamily(displayedProtocol(i)) == family
                else -> pcapProtocolFamily(displayedProtocol(i)) == family
            }
        }
    }
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
