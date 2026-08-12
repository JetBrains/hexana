package org.jetbrains.hexana.shared

import org.jetbrains.hexana.pcap.PcapDissectedPacket
import org.jetbrains.hexana.pcap.PcapDissector
import org.jetbrains.hexana.pcap.PcapFile
import org.jetbrains.hexana.pcap.TlsDecryptionContext
import org.jetbrains.hexana.pcap.TlsDecryptor
import org.jetbrains.hexana.pcap.TlsKeyDeriver
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
     * Builds the packets tab with TLS decryption support. Called when a
     * [TlsDecryptor] and [TlsKeyDeriver] are available (JVM).
     */
    fun tableTabsWithDecryption(
        provider: PcapBinaryProvider,
        decryptor: TlsDecryptor,
        keyDeriver: TlsKeyDeriver,
    ): List<TableTabDescriptor> {
        val pcap = provider.parsedPcapFile ?: return emptyList()
        return listOfNotNull(
            pcapHeaderTab(pcap, provider),
            pcapPacketsTab(pcap, provider, decryptor, keyDeriver),
        )
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
    decryptor: TlsDecryptor? = null,
    keyDeriver: TlsKeyDeriver? = null,
): TableTabDescriptor? {
    val packets = pcap.packets
    if (packets.isEmpty()) return null

    val nsResolution = pcap.header.magic == org.jetbrains.hexana.pcap.PCAP_MAGIC_NSEC_LE ||
        pcap.header.magic == org.jetbrains.hexana.pcap.PCAP_MAGIC_NSEC_BE

    val linkType = pcap.header.network
    val hasKeyLog = provider.sslKeyLog.isNotEmpty

    // Build decryption context when key log and crypto support are available
    val decryptionContext: TlsDecryptionContext? = if (hasKeyLog && decryptor != null && keyDeriver != null) {
        try {
            TlsDecryptionContext.build(
                buffer = provider.bytes,
                packets = packets,
                linkType = linkType,
                sslKeyLog = provider.sslKeyLog,
                tlsSessionMap = provider.tlsSessionMap,
                decryptor = decryptor,
                keyDeriver = keyDeriver,
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
                d.protocol.startsWith("TLS") && d.info.contains("Application Data") -> "🔒 encrypted"
                else -> ""
            }
            CellValue(text)
        })
    }

    val prologueText = if (hasKeyLog) {
        "🔑 SSL key log loaded (${provider.sslKeyLog.sessionCount} sessions)"
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
    )
}
