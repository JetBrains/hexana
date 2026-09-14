package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.pcap.PcapFile
import org.jetbrains.hexana.pcap.PcapGlobalHeader
import org.jetbrains.hexana.pcap.PcapNgFile
import org.jetbrains.hexana.pcap.PcapNgParser
import org.jetbrains.hexana.pcap.PcapPacketRecord
import org.jetbrains.hexana.pcap.PcapParser
import org.jetbrains.hexana.pcap.PcapDissector
import org.jetbrains.hexana.pcap.SslKeyLog
import org.jetbrains.hexana.pcap.TcpStreamKey
import org.jetbrains.hexana.pcap.buildTlsSessionMap

/**
 * Binary provider for PCAP and PCAP-NG capture files. Extends [GenericBinaryProvider]
 * so the hex view works as before, and adds lazy properties that parse the capture
 * structure once and cache the result for all panels.
 */
class PcapBinaryProvider(
    sharedBytes: CommonByteBuffer,
    /**
     * Path of the capture on disk, when it came from one. Hosts use it to
     * key per-capture settings - the remembered SSL key log - so it is null
     * for in-memory captures (an entry unpacked out of an archive), which
     * have no path to remember anything against.
     */
    val sourcePath: String? = null,
) : GenericBinaryProvider(sharedBytes) {

    /** `true` when the file is PCAP-NG rather than classic PCAP. */
    val isPcapNg: Boolean by lazy { PcapNgParser.isPcapNg(bytes.position(0)) }

    /**
     * Lazily parsed PCAP file structure, or `null` when parsing fails.
     * Works for both classic PCAP and PCAP-NG files.
     */
    val parsedPcapFile: PcapFile? by lazy {
        try {
            if (isPcapNg) {
                // Convert PcapNgFile → PcapFile for uniform downstream consumption
                val ng = PcapNgParser.parse(bytes.position(0)) ?: return@lazy null
                PcapFile(ng.header, ng.packets)
            } else {
                PcapParser.parse(bytes.position(0))
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Lazily parsed PCAP-NG file with interface details, or `null`. */
    val parsedPcapNgFile: PcapNgFile? by lazy {
        if (!isPcapNg) return@lazy null
        try {
            PcapNgParser.parse(bytes.position(0))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The file header followed by one range per captured packet.
     *
     * The header range runs up to the first packet, which covers the 24-byte global
     * header of a classic capture and the section header plus interface description
     * blocks of a PCAP-NG one, without either format's layout being hardcoded here.
     * For PCAP-NG the per-packet range stops at the end of the captured bytes, so a
     * block's trailing options are left untinted.
     */
    override val structureRanges: List<StructureRange> by lazy {
        val packets = parsedPcapFile?.packets?.takeIf { it.isNotEmpty() } ?: return@lazy emptyList()
        val total = bytes.size
        buildList {
            structureRangeOrNull(0L, packets.first().fileOffset, "Header", total, tabName = "Header")
                ?.let { add(it) }
            packets.forEachIndexed { index, packet ->
                structureRangeOrNull(
                    offset = packet.fileOffset,
                    size = packet.dataOffset - packet.fileOffset + packet.inclLen,
                    // One-based, matching the "No." column of the Packets table.
                    label = "Packet ${index + 1}",
                    fileSize = total,
                    tabName = "Packets",
                )?.let { add(it) }
            }
        }
    }

    /**
     * SSL key log loaded for this capture. When set, TLS Application Data
     * packets can be decrypted and their plaintext shown in the UI.
     * Updated from the IDE layer when the user loads a key file or one is
     * auto-detected next to the PCAP file.
     */
    var sslKeyLog: SslKeyLog = SslKeyLog.EMPTY

    /**
     * `true` when at least one packet in the capture contains a TLS record.
     * Used to decide whether to show the "Load SSL Key Log" button.
     */
    val hasEncryptedPackets: Boolean by lazy {
        val pcap = parsedPcapFile ?: return@lazy false
        val linkType = pcap.header.network
        pcap.packets.any { packet ->
            try {
                PcapDissector.protocolInfo(bytes, packet, linkType) == "TLS"
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Map from canonical TCP stream key to hex-encoded client random.
     * Built lazily after the PCAP is parsed; used to correlate TLS
     * sessions with SSLKEYLOG entries.
     */
    val tlsSessionMap: Map<TcpStreamKey, String> by lazy {
        val pcap = parsedPcapFile ?: return@lazy emptyMap()
        buildTlsSessionMap(bytes, pcap.packets, pcap.header.network)
    }

    companion object {
        /** Detects whether [buffer] starts with a recognised PCAP or PCAP-NG magic. */
        fun isPcapFile(buffer: CommonByteBuffer): Boolean =
            PcapParser.isPcap(buffer) || PcapNgParser.isPcapNg(buffer)
    }
}
