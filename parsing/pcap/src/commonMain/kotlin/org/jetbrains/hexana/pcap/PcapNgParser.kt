package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Parses PCAP-NG (`.pcapng`) capture files on top of [CommonByteBuffer].
 *
 * PCAP-NG is a block-based format. The first block must be a Section Header
 * Block (SHB) with magic `0x0A0D0D0A`. Each block starts with a type (u32),
 * total length (u32), body, and a trailing copy of the total length.
 *
 * Reference: https://www.ietf.org/archive/id/draft-tuexen-opsawg-pcapng-05.html
 */
object PcapNgParser {

    /** Section Header Block type. */
    private const val BT_SHB = 0x0A0D0D0AL

    /** Interface Description Block type. */
    private const val BT_IDB = 1L

    /** Enhanced Packet Block type. */
    private const val BT_EPB = 6L

    /** Simple Packet Block type. */
    private const val BT_SPB = 3L

    /**
     * Byte-order magic inside SHB body, as read by [readRawU32] (little-endian reader).
     * On-disk LE: `4D 3C 2B 1A` → readRawU32 → `0x1A2B3C4D`
     * On-disk BE: `1A 2B 3C 4D` → readRawU32 → `0x4D3C2B1A`
     */
    private const val SHB_BOM_LE = 0x1A2B3C4DL
    private const val SHB_BOM_BE = 0x4D3C2B1AL

    /** Option code for if_tsresol in IDB. */
    private const val OPT_IF_TSRESOL = 9

    /**
     * Returns `true` when [buffer] starts with a PCAP-NG Section Header Block.
     */
    fun isPcapNg(buffer: CommonByteBuffer): Boolean {
        if (buffer.size < 12) return false
        buffer.position(0)
        val blockType = readRawU32(buffer)
        if (blockType != BT_SHB) return false
        // Skip block total length (4 bytes)
        readRawU32(buffer)
        val bom = readRawU32(buffer)
        return bom == SHB_BOM_LE || bom == SHB_BOM_BE
    }

    /**
     * Parses a PCAP-NG file into a [PcapFile] with a synthetic global header
     * and packet records, so the existing dissector and table tabs work unchanged.
     *
     * Returns `null` when the file is not valid PCAP-NG.
     */
    fun parse(buffer: CommonByteBuffer): PcapNgFile? {
        if (buffer.size < 28) return null

        buffer.position(0)
        val blockType = readRawU32(buffer)
        if (blockType != BT_SHB) return null

        val shbTotalLen = readRawU32(buffer)
        val bom = readRawU32(buffer)
        val bigEndian = when (bom) {
            SHB_BOM_LE -> false
            SHB_BOM_BE -> true
            else -> return null
        }

        // Re-read SHB total length with correct endianness if swapped
        buffer.position(4)
        val shbLen = readU32(buffer, bigEndian)
        // SHB body: BOM(4) + major(2) + minor(2) + section_length(8) + options
        buffer.position(12)
        val versionMajor = readU16(buffer, bigEndian)
        val versionMinor = readU16(buffer, bigEndian)
        // section_length (8 bytes) — skip
        readU32(buffer, bigEndian)
        readU32(buffer, bigEndian)

        // Collect interfaces and packets
        val interfaces = mutableListOf<PcapNgInterface>()
        val packets = mutableListOf<PcapPacketRecord>()
        var offset = alignTo4(shbLen)
        val size = buffer.size.toLong()

        while (offset + 8 <= size && packets.size < MAX_PACKETS) {
            buffer.position(offset.toInt())
            val bt = readU32(buffer, bigEndian)
            val totalLen = readU32(buffer, bigEndian)
            if (totalLen < 12 || offset + totalLen > size) break

            when (bt) {
                BT_SHB -> {
                    // New section — reset interfaces
                    interfaces.clear()
                }
                BT_IDB -> parseIdb(buffer, offset, totalLen, bigEndian, interfaces)
                BT_EPB -> parseEpb(buffer, offset, totalLen, bigEndian, interfaces, packets)
                BT_SPB -> parseSpb(buffer, offset, totalLen, bigEndian, interfaces, packets)
            }

            offset += alignTo4(totalLen)
        }

        // Build a synthetic global header from the first interface (or defaults)
        val firstIface = interfaces.firstOrNull()
        val linkType = firstIface?.linkType ?: 1L
        val snapLen = firstIface?.snapLen ?: 0L

        val header = PcapGlobalHeader(
            magic = PCAP_MAGIC_LE, // synthetic — not a real PCAP magic
            versionMajor = versionMajor,
            versionMinor = versionMinor,
            thisZone = 0,
            sigFigs = 0,
            snapLen = snapLen,
            network = linkType,
        )

        return PcapNgFile(
            header = header,
            packets = packets,
            interfaces = interfaces,
            isPcapNg = true,
        )
    }

    private fun parseIdb(
        buffer: CommonByteBuffer, blockOffset: Long, totalLen: Long,
        bigEndian: Boolean, interfaces: MutableList<PcapNgInterface>,
    ) {
        // IDB body starts at blockOffset + 8
        val bodyOffset = blockOffset + 8
        buffer.position(bodyOffset.toInt())
        val linkType = readU16(buffer, bigEndian).toLong()
        // reserved 2 bytes
        readU16(buffer, bigEndian)
        val snapLen = readU32(buffer, bigEndian)

        // Parse options for timestamp resolution
        var tsResol = 6 // default: microseconds (10^-6)
        val optionsStart = bodyOffset + 8
        val optionsEnd = blockOffset + totalLen - 4 // before trailing length
        parseOptions(buffer, optionsStart, optionsEnd, bigEndian) { code, value ->
            if (code == OPT_IF_TSRESOL && value.isNotEmpty()) {
                val raw = value[0].toInt() and 0xFF
                tsResol = if (raw and 0x80 != 0) {
                    // Power of 2
                    -(raw and 0x7F) // negative signals base-2
                } else {
                    raw // Power of 10
                }
            }
        }

        interfaces.add(PcapNgInterface(linkType, snapLen, tsResol))
    }

    private fun parseEpb(
        buffer: CommonByteBuffer, blockOffset: Long, totalLen: Long,
        bigEndian: Boolean, interfaces: List<PcapNgInterface>,
        packets: MutableList<PcapPacketRecord>,
    ) {
        // EPB body: interface_id(4) + ts_high(4) + ts_low(4) + cap_len(4) + orig_len(4)
        val bodyOffset = blockOffset + 8
        buffer.position(bodyOffset.toInt())
        val interfaceId = readU32(buffer, bigEndian).toInt()
        val tsHigh = readU32(buffer, bigEndian)
        val tsLow = readU32(buffer, bigEndian)
        val capLen = readU32(buffer, bigEndian)
        val origLen = readU32(buffer, bigEndian)

        val iface = interfaces.getOrNull(interfaceId)
        val tsResol = iface?.tsResol ?: 6

        val timestamp = (tsHigh shl 32) or tsLow
        val (tsSec, tsUsec) = splitTimestamp(timestamp, tsResol)

        // Packet data starts at bodyOffset + 20
        packets.add(
            PcapPacketRecord(
                tsSec = tsSec,
                tsUsec = tsUsec,
                inclLen = capLen,
                origLen = origLen,
                fileOffset = blockOffset,
                dataOffset = bodyOffset + 20,
            )
        )
    }

    private fun parseSpb(
        buffer: CommonByteBuffer, blockOffset: Long, totalLen: Long,
        bigEndian: Boolean, interfaces: List<PcapNgInterface>,
        packets: MutableList<PcapPacketRecord>,
    ) {
        // SPB body: orig_len(4) + packet_data
        val bodyOffset = blockOffset + 8
        buffer.position(bodyOffset.toInt())
        val origLen = readU32(buffer, bigEndian)
        val snapLen = interfaces.firstOrNull()?.snapLen ?: origLen
        val capLen = minOf(origLen, snapLen)

        packets.add(
            PcapPacketRecord(
                tsSec = 0,
                tsUsec = 0,
                inclLen = capLen,
                origLen = origLen,
                fileOffset = blockOffset,
                dataOffset = bodyOffset + 4,
            )
        )
    }

    /**
     * Splits a raw PCAP-NG timestamp into seconds and fractional part (µs or ns).
     */
    private fun splitTimestamp(raw: Long, tsResol: Int): Pair<Long, Long> {
        if (tsResol < 0) {
            // Base-2: 2^(-tsResol) ticks per second
            val ticksPerSec = 1L shl (-tsResol)
            val sec = raw / ticksPerSec
            val frac = raw % ticksPerSec
            // Convert to microseconds
            val usec = frac * 1_000_000L / ticksPerSec
            return sec to usec
        }
        // Base-10: 10^tsResol ticks per second
        val divisor = pow10(tsResol)
        val sec = raw / divisor
        val frac = raw % divisor
        // Normalize to microseconds
        val usec = when {
            tsResol <= 6 -> frac * pow10(6 - tsResol)
            else -> frac / pow10(tsResol - 6)
        }
        return sec to usec
    }

    private fun pow10(n: Int): Long {
        var result = 1L
        repeat(n) { result *= 10 }
        return result
    }

    private fun alignTo4(value: Long): Long = (value + 3) and 3L.inv()

    private fun parseOptions(
        buffer: CommonByteBuffer, start: Long, end: Long,
        bigEndian: Boolean, handler: (code: Int, value: ByteArray) -> Unit,
    ) {
        var pos = start
        while (pos + 4 <= end) {
            buffer.position(pos.toInt())
            val code = readU16(buffer, bigEndian)
            val length = readU16(buffer, bigEndian)
            if (code == 0) break // opt_endofopt
            if (pos + 4 + length > end) break
            val value = ByteArray(length)
            for (i in 0 until length) {
                value[i] = buffer.get()
            }
            handler(code, value)
            pos += 4 + alignTo4(length.toLong())
        }
    }

    private const val MAX_PACKETS = MAX_CAPTURE_PACKETS

    // ---- low-level readers ----

    /** Reads a u32 in file byte order (always little-endian for raw magic detection). */
    private fun readRawU32(buffer: CommonByteBuffer): Long {
        val b0 = buffer.get().toLong() and 0xFF
        val b1 = buffer.get().toLong() and 0xFF
        val b2 = buffer.get().toLong() and 0xFF
        val b3 = buffer.get().toLong() and 0xFF
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
}

/**
 * Parsed PCAP-NG file, reusing [PcapGlobalHeader] and [PcapPacketRecord]
 * so the dissector and table tabs work without changes.
 */
data class PcapNgFile(
    val header: PcapGlobalHeader,
    val packets: List<PcapPacketRecord>,
    val interfaces: List<PcapNgInterface>,
    val isPcapNg: Boolean = true,
)

/**
 * Interface description from a PCAP-NG Interface Description Block.
 */
data class PcapNgInterface(
    val linkType: Long,
    val snapLen: Long,
    /** Timestamp resolution: positive = power of 10, negative = power of 2. */
    val tsResol: Int,
)
