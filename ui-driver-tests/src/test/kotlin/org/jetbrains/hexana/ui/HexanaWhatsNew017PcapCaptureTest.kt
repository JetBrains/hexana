package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.HexanaProviderKind
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.minutes

@Tag("screenshot")
class HexanaWhatsNew017PcapCaptureTest {
    @Test
    fun capturePcapNgPacketsTab() {
        val fixture = Files.createTempFile("hexana-whatsnew-017-packets-", ".pcapng").also {
            it.writeBytes(pcapNgFixture())
        }
        hexanaIde(
            testName = "hexana-whats-new-017-pcap-packets-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            val editor = openAbsoluteFile(fixture)
            waitForPcapPanels(editor)
            editor.selectTab("Packets")
            waitForSelectedPacketsTab(editor)
            copyScreenshot(driver, "pcap-packets", "pcap-packets.png")
        }
    }

    private fun waitForPcapPanels(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 2.minutes.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (
                lastSnapshot.providerKind == HexanaProviderKind.GENERIC.id &&
                lastSnapshot.panelsResolved &&
                setOf("Header", "Packets").all(lastSnapshot.panelNames::contains)
            ) return
            Thread.sleep(250)
        }
        error("Timed out waiting for PCAP-NG Header and Packets tabs: $lastSnapshot")
    }

    private fun waitForSelectedPacketsTab(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 30_000_000_000L
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.selectedTabName == "Packets") return
            Thread.sleep(250)
        }
        error("Timed out waiting for selected Packets tab: $lastSnapshot")
    }

    private fun pcapNgFixture(): ByteArray =
        sectionHeaderBlock() + interfaceDescriptionBlock() + listOf(
            ethernetIpv4TcpPacket(
                source = intArrayOf(10, 0, 0, 10),
                destination = intArrayOf(10, 0, 0, 20),
                sourcePort = 49_152,
                destinationPort = 80,
                payload = "GET /release HTTP/1.1\\r\\nHost: hexana.test\\r\\n\\r\\n".encodeToByteArray(),
            ),
            ethernetIpv4TcpPacket(
                source = intArrayOf(10, 0, 0, 20),
                destination = intArrayOf(10, 0, 0, 10),
                sourcePort = 80,
                destinationPort = 49_152,
                payload = "HTTP/1.1 200 OK\\r\\nContent-Type: text/plain\\r\\nContent-Length: 14\\r\\n\\r\\nHello, Hexana!".encodeToByteArray(),
            ),
            ethernetIpv4TcpPacket(
                source = intArrayOf(10, 0, 0, 10),
                destination = intArrayOf(10, 0, 0, 20),
                sourcePort = 49_153,
                destinationPort = 80,
                payload = "GET /packets HTTP/1.1\\r\\nHost: hexana.test\\r\\n\\r\\n".encodeToByteArray(),
            ),
            ethernetIpv4TcpPacket(
                source = intArrayOf(10, 0, 0, 20),
                destination = intArrayOf(10, 0, 0, 10),
                sourcePort = 80,
                destinationPort = 49_153,
                payload = "HTTP/1.1 204 No Content\\r\\nContent-Length: 0\\r\\n\\r\\n".encodeToByteArray(),
            ),
        ).mapIndexed { index, packet -> enhancedPacketBlock(timestampMicros = (index + 1) * 1_000_000L, packet) }
            .fold(ByteArray(0)) { all, block -> all + block }

    private fun sectionHeaderBlock(): ByteArray = ByteArray(28).also { bytes ->
        putU32Le(bytes, 0, 0x0A0D0D0A)
        putU32Le(bytes, 4, 28)
        putU32Le(bytes, 8, 0x1A2B3C4D)
        putU16Le(bytes, 12, 1)
        putU16Le(bytes, 14, 0)
        (16..23).forEach { bytes[it] = 0xFF.toByte() }
        putU32Le(bytes, 24, 28)
    }

    private fun interfaceDescriptionBlock(): ByteArray = ByteArray(20).also { bytes ->
        putU32Le(bytes, 0, 1)
        putU32Le(bytes, 4, 20)
        putU16Le(bytes, 8, 1)
        putU32Le(bytes, 12, 65_535)
        putU32Le(bytes, 16, 20)
    }

    private fun enhancedPacketBlock(timestampMicros: Long, packet: ByteArray): ByteArray {
        val paddedSize = (packet.size + 3) and 3.inv()
        val totalSize = 32 + paddedSize
        return ByteArray(totalSize).also { bytes ->
            putU32Le(bytes, 0, 6)
            putU32Le(bytes, 4, totalSize.toLong())
            putU32Le(bytes, 8, 0)
            putU32Le(bytes, 12, timestampMicros ushr 32)
            putU32Le(bytes, 16, timestampMicros and 0xFFFF_FFFFL)
            putU32Le(bytes, 20, packet.size.toLong())
            putU32Le(bytes, 24, packet.size.toLong())
            packet.copyInto(bytes, destinationOffset = 28)
            putU32Le(bytes, totalSize - 4, totalSize.toLong())
        }
    }

    private fun ethernetIpv4TcpPacket(
        source: IntArray,
        destination: IntArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val bytes = ByteArray(14 + 20 + 20 + payload.size)
        intArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x20).forEachIndexed { index, value -> bytes[index] = value.toByte() }
        intArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x10).forEachIndexed { index, value -> bytes[6 + index] = value.toByte() }
        putU16Be(bytes, 12, 0x0800)
        val ipOffset = 14
        bytes[ipOffset] = 0x45
        putU16Be(bytes, ipOffset + 2, 20 + 20 + payload.size)
        bytes[ipOffset + 8] = 64
        bytes[ipOffset + 9] = 6
        source.forEachIndexed { index, value -> bytes[ipOffset + 12 + index] = value.toByte() }
        destination.forEachIndexed { index, value -> bytes[ipOffset + 16 + index] = value.toByte() }
        val tcpOffset = ipOffset + 20
        putU16Be(bytes, tcpOffset, sourcePort)
        putU16Be(bytes, tcpOffset + 2, destinationPort)
        bytes[tcpOffset + 12] = 0x50
        bytes[tcpOffset + 13] = 0x18
        putU16Be(bytes, tcpOffset + 14, 65_535)
        payload.copyInto(bytes, destinationOffset = tcpOffset + 20)
        return bytes
    }

    private fun putU16Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32Le(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun putU16Be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }
        val target = repoRoot().resolve("docs/whatsnew/captures/0.17").createDirectories().resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.17 screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) path = path.parent ?: error("Could not locate repository root")
        return path
    }
}
