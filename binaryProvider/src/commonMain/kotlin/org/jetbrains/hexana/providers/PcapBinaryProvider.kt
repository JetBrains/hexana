package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.pcap.PcapFile
import org.jetbrains.hexana.pcap.PcapGlobalHeader
import org.jetbrains.hexana.pcap.PcapNgFile
import org.jetbrains.hexana.pcap.PcapNgParser
import org.jetbrains.hexana.pcap.PcapPacketRecord
import org.jetbrains.hexana.pcap.PcapParser

/**
 * Binary provider for PCAP and PCAP-NG capture files. Extends [GenericBinaryProvider]
 * so the hex view works as before, and adds lazy properties that parse the capture
 * structure once and cache the result for all panels.
 */
class PcapBinaryProvider(
    sharedBytes: CommonByteBuffer,
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

    companion object {
        /** Detects whether [buffer] starts with a recognised PCAP or PCAP-NG magic. */
        fun isPcapFile(buffer: CommonByteBuffer): Boolean =
            PcapParser.isPcap(buffer) || PcapNgParser.isPcapNg(buffer)
    }
}
