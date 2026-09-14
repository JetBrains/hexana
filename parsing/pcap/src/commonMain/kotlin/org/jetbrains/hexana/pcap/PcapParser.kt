package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Parses PCAP capture files on top of [CommonByteBuffer].
 *
 * PCAP files may be either little-endian or big-endian; the magic number
 * at offset 0 determines the byte order for the rest of the file.
 *
 * Reference: https://www.tcpdump.org/manpages/pcap-savefile.5.txt
 */
object PcapParser {

    /**
     * Global header size in bytes. Public because a capture with no packet records has
     * no other way to say where its header ends.
     */
    const val GLOBAL_HEADER_SIZE = 24

    /** Per-packet record header size in bytes. */
    private const val PACKET_HEADER_SIZE = 16

    /**
     * Returns `true` when [buffer] starts with a recognised PCAP magic number.
     */
    fun isPcap(buffer: CommonByteBuffer): Boolean {
        if (buffer.size < GLOBAL_HEADER_SIZE) return false
        val magic = readMagic(buffer)
        return magic == PCAP_MAGIC_LE || magic == PCAP_MAGIC_BE ||
            magic == PCAP_MAGIC_NSEC_LE || magic == PCAP_MAGIC_NSEC_BE
    }

    /**
     * Parses the PCAP file. Returns `null` when the magic is unrecognised.
     */
    fun parse(buffer: CommonByteBuffer): PcapFile? {
        if (buffer.size < GLOBAL_HEADER_SIZE) return null
        val header = parseGlobalHeader(buffer) ?: return null
        val packets = parsePackets(buffer, header)
        return PcapFile(header, packets)
    }

    /**
     * Parses only the 24-byte global header. Returns `null` on bad magic.
     */
    fun parseGlobalHeader(buffer: CommonByteBuffer): PcapGlobalHeader? {
        val magic = readMagic(buffer)
        val bigEndian = when (magic) {
            PCAP_MAGIC_LE, PCAP_MAGIC_NSEC_LE -> false
            PCAP_MAGIC_BE, PCAP_MAGIC_NSEC_BE -> true
            else -> return null
        }
        buffer.position(4)
        val versionMajor = readU16(buffer, bigEndian)
        val versionMinor = readU16(buffer, bigEndian)
        val thisZone = readI32(buffer, bigEndian)
        val sigFigs = readU32(buffer, bigEndian)
        val snapLen = readU32(buffer, bigEndian)
        val network = readU32(buffer, bigEndian)
        return PcapGlobalHeader(
            magic = magic,
            versionMajor = versionMajor,
            versionMinor = versionMinor,
            thisZone = thisZone,
            sigFigs = sigFigs,
            snapLen = snapLen,
            network = network,
        )
    }

    /**
     * Maximum number of packet records to parse.
     * Large capture files can contain millions of packets; parsing all of them
     * eagerly would freeze the IDE. We keep a generous but bounded limit that
     * is sufficient for summary display while staying responsive.
     */
    private const val MAX_PACKETS = 10_000

    private fun parsePackets(buffer: CommonByteBuffer, header: PcapGlobalHeader): List<PcapPacketRecord> {
        val bigEndian = header.bigEndian
        val packets = mutableListOf<PcapPacketRecord>()
        var offset = GLOBAL_HEADER_SIZE.toLong()
        val size = buffer.size.toLong()
        while (offset + PACKET_HEADER_SIZE <= size && packets.size < MAX_PACKETS) {
            buffer.position(offset.toInt())
            val tsSec = readU32(buffer, bigEndian)
            val tsUsec = readU32(buffer, bigEndian)
            val inclLen = readU32(buffer, bigEndian)
            val origLen = readU32(buffer, bigEndian)
            if (inclLen < 0 || offset + PACKET_HEADER_SIZE + inclLen > size) break
            packets.add(
                PcapPacketRecord(
                    tsSec = tsSec,
                    tsUsec = tsUsec,
                    inclLen = inclLen,
                    origLen = origLen,
                    fileOffset = offset,
                )
            )
            offset += PACKET_HEADER_SIZE + inclLen
        }
        return packets
    }

    // ---- low-level readers ----

    private fun readMagic(buffer: CommonByteBuffer): Long {
        buffer.position(0)
        val b0 = buffer.get().toLong() and 0xFF
        val b1 = buffer.get().toLong() and 0xFF
        val b2 = buffer.get().toLong() and 0xFF
        val b3 = buffer.get().toLong() and 0xFF
        // Read as little-endian u32 to match PCAP_MAGIC_LE directly
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readU16(buffer: CommonByteBuffer, bigEndian: Boolean): Int {
        val b0 = buffer.get().toInt() and 0xFF
        val b1 = buffer.get().toInt() and 0xFF
        return if (bigEndian) (b0 shl 8) or b1 else b0 or (b1 shl 8)
    }

    private fun readU32(buffer: CommonByteBuffer, bigEndian: Boolean): Long {
        val b0 = buffer.get().toLong() and 0xFF
        val b1 = buffer.get().toLong() and 0xFF
        val b2 = buffer.get().toLong() and 0xFF
        val b3 = buffer.get().toLong() and 0xFF
        return if (bigEndian) {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        } else {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
    }

    private fun readI32(buffer: CommonByteBuffer, bigEndian: Boolean): Int {
        return readU32(buffer, bigEndian).toInt()
    }
}
