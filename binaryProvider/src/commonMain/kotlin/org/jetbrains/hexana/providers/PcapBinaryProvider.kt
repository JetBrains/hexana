package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.pcap.PcapFile
import org.jetbrains.hexana.pcap.PcapParser

/**
 * Binary provider for PCAP capture files. Extends [GenericBinaryProvider] so the
 * hex view works as before, and adds a [parsedPcapFile] lazy property that parses
 * the PCAP structure once and caches the result for all panels.
 */
class PcapBinaryProvider(
    sharedBytes: CommonByteBuffer,
) : GenericBinaryProvider(sharedBytes) {

    /**
     * Lazily parsed PCAP file structure, or `null` when parsing fails.
     */
    val parsedPcapFile: PcapFile? by lazy {
        try {
            PcapParser.parse(bytes.position(0))
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** Detects whether [buffer] starts with a recognised PCAP magic number. */
        fun isPcapFile(buffer: CommonByteBuffer): Boolean = PcapParser.isPcap(buffer)
    }
}
