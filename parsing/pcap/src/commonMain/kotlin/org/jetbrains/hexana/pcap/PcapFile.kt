package org.jetbrains.hexana.pcap

/**
 * Most packets [PcapParser] and [PcapNgParser] list. Large captures can hold millions;
 * parsing them all eagerly would freeze the IDE, and this is enough for a summary.
 */
const val MAX_CAPTURE_PACKETS: Int = 10_000

/**
 * Parsed PCAP capture file. See
 * [PCAP format](https://www.tcpdump.org/manpages/pcap-savefile.5.txt).
 */
data class PcapFile(
    val header: PcapGlobalHeader,
    val packets: List<PcapPacketRecord>,
)

/**
 * Global header at the start of every PCAP file (24 bytes).
 */
data class PcapGlobalHeader(
    /** Magic number — `0xA1B2C3D4` (native) or `0xD4C3B2A1` (swapped). */
    val magic: Long,
    /** Major version (typically 2). */
    val versionMajor: Int,
    /** Minor version (typically 4). */
    val versionMinor: Int,
    /** GMT-to-local correction (usually 0). */
    val thisZone: Int,
    /** Accuracy of timestamps (usually 0). */
    val sigFigs: Long,
    /** Max length of captured packets, in octets. */
    val snapLen: Long,
    /** Data link type (e.g. 1 = Ethernet). */
    val network: Long,
) {
    /** `true` when the file was written in big-endian byte order. */
    val bigEndian: Boolean get() = magic == PCAP_MAGIC_BE

    /** Version string, e.g. `"2.4"`. */
    val version: String get() = "$versionMajor.$versionMinor"

    /** Human-readable link-layer type name. */
    val networkName: String get() = linkTypeNames[network.toInt()] ?: "Unknown ($network)"
}

/**
 * Per-packet record header (16 bytes) followed by captured data.
 */
data class PcapPacketRecord(
    /** Timestamp seconds. */
    val tsSec: Long,
    /** Timestamp microseconds (or nanoseconds for nanosecond-resolution files). */
    val tsUsec: Long,
    /** Number of octets of packet data actually captured and saved in the file. */
    val inclLen: Long,
    /** Original length of the packet on the wire. */
    val origLen: Long,
    /** Byte offset of this packet record in the file. */
    val fileOffset: Long,
    /**
     * Byte offset where the captured packet data starts.
     * For classic PCAP this is `fileOffset + 16` (after the 16-byte record header).
     * For PCAP-NG this points into the block body (after the EPB/SPB header fields).
     */
    val dataOffset: Long = fileOffset + 16,
)

/** PCAP magic for native (little-endian) byte order. */
const val PCAP_MAGIC_LE: Long = 0xA1B2C3D4L

/** PCAP magic for swapped (big-endian) byte order. */
const val PCAP_MAGIC_BE: Long = 0xD4C3B2A1L

/** PCAP magic for nanosecond-resolution, native byte order. */
const val PCAP_MAGIC_NSEC_LE: Long = 0xA1B23C4DL

/** PCAP magic for nanosecond-resolution, swapped byte order. */
const val PCAP_MAGIC_NSEC_BE: Long = 0x4D3CB2A1L

/** Well-known PCAP link-layer type names. */
private val linkTypeNames = mapOf(
    0 to "NULL",
    1 to "Ethernet",
    6 to "Token Ring",
    9 to "PPP",
    101 to "Raw IP",
    105 to "IEEE 802.11",
    113 to "Linux cooked capture",
    127 to "IEEE 802.11 (radiotap)",
    162 to "Linux cooked capture v2",
    228 to "Raw IPv4",
    229 to "Raw IPv6",
)
