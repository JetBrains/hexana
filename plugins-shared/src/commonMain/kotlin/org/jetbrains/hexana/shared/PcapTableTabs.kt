package org.jetbrains.hexana.shared

import org.jetbrains.hexana.pcap.PcapDissectedPacket
import org.jetbrains.hexana.pcap.PcapDissector
import org.jetbrains.hexana.pcap.PcapFile
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.PcapBinaryProvider

class PcapTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is PcapBinaryProvider) return emptyList()
        val pcap = provider.parsedPcapFile ?: return emptyList()
        return listOfNotNull(
            pcapHeaderTab(pcap),
            pcapPacketsTab(pcap, provider),
        )
    }
}

private fun pcapHeaderTab(pcap: PcapFile): TableTabDescriptor {
    val h = pcap.header
    val rows = listOf(
        "Version" to h.version,
        "Byte order" to if (h.bigEndian) "Big-endian" else "Little-endian",
        "Snap length" to "${h.snapLen}",
        "Link-layer type" to h.networkName,
        "Packets" to "${pcap.packets.size}",
    )
    return TableTabDescriptor(
        name = "Header",
        columns = listOf(
            col("Property", ColumnWidth.MeasuredMin("Link-layer type")) { CellValue(rows[it].first) },
            col("Value", ColumnWidth.Fill()) { CellValue(rows[it].second) },
        ),
        rowCount = rows.size,
        showIdColumn = false,
        priority = TabPriority.High,
        prologueText = "PCAP Capture File",
    )
}

private const val PACKET_HEADER_SIZE = 16

private fun pcapPacketsTab(pcap: PcapFile, provider: PcapBinaryProvider): TableTabDescriptor? {
    val packets = pcap.packets
    if (packets.isEmpty()) return null

    val nsResolution = pcap.header.magic == org.jetbrains.hexana.pcap.PCAP_MAGIC_NSEC_LE ||
        pcap.header.magic == org.jetbrains.hexana.pcap.PCAP_MAGIC_NSEC_BE

    val linkType = pcap.header.network

    // Lazily dissect packets on first access per row
    val dissectedCache = HashMap<Int, PcapDissectedPacket>()
    fun dissected(index: Int): PcapDissectedPacket = dissectedCache.getOrPut(index) {
        try {
            PcapDissector.dissect(provider.bytes, packets[index], linkType)
        } catch (_: Exception) {
            PcapDissectedPacket("", "", "?", "")
        }
    }

    return TableTabDescriptor(
        name = "Packets",
        columns = listOf(
            col("No.", ColumnWidth.MeasuredMin("99999")) { CellValue(it + 1) },
            col("Time", ColumnWidth.MeasuredMin("0000000000.000000")) { i ->
                val p = packets[i]
                val frac = if (nsResolution) {
                    p.tsUsec.toString().padStart(9, '0')
                } else {
                    p.tsUsec.toString().padStart(6, '0')
                }
                CellValue("${p.tsSec}.$frac")
            },
            col("Source", ColumnWidth.MeasuredMin("000.000.000.000")) { CellValue(dissected(it).source) },
            col("Destination", ColumnWidth.MeasuredMin("000.000.000.000")) { CellValue(dissected(it).destination) },
            col("Protocol", ColumnWidth.MeasuredMin("Protocol")) { CellValue(dissected(it).protocol) },
            col("Length", ColumnWidth.MeasuredMin("Length")) { CellValue(packets[it].origLen) },
            col("Info", ColumnWidth.Fill()) { CellValue(dissected(it).info) },
        ),
        rowCount = packets.size,
        defaultSortColumn = 0,
        alwaysShowSearch = true,
        showIdColumn = false,
        searchPlaceholder = "Search packets",
        priority = TabPriority.High,
        rowSelectionProvider = { i ->
            val p = packets[i]
            val offset = p.fileOffset.toInt()
            val size = (PACKET_HEADER_SIZE + p.inclLen).toInt()
            RowSelectionData(offset, size)
        },
    )
}
