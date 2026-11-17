package org.jetbrains.hexana.ui

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.UIComponentsList.Companion.waitAny
import com.intellij.driver.sdk.ui.ui
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

/** Release 0.22 artwork only; excluded from the normal UI suite by the screenshot tag. */
@Tag("screenshot")
class HexanaWhatsNew022CaptureTest {
    /** Review evidence from the final ZIP; source feature images are captured separately. */
    @Test
    fun captureWhatsNewPage() {
        val project = Files.createTempDirectory("hexana-022-page-")
        hexanaIde("hexana-022-page", projectDir = project, configure = { showWhatsNewOnNewVersion(false) }) {
            maximizeWindow()
            closeProjectToolWindow()
            closeServicesToolWindow()
            driver.invokeAction("hexana.showWhatsNew")
            driver.ui.x { contains(byText("What's New in Hexana 0.22")) }.waitFound(120.seconds)
            clearNotifications()
            val page = driver.ui.x { byClass("ComposePanel") }.waitFound(30.seconds)
            val output = Path.of(checkNotNull(System.getenv("AIR_ARTIFACTS_DIR")))
                .resolve("whatsnew-022-ide-page").createDirectories()
            // Deliberately overlapping viewports; the resulting sequence must also be visually
            // inspected for complete coverage before selecting the images posted to review.
            for (expanded in listOf(false, true)) {
                page.robot.rotateMouseWheel(page.component, -200)
                Thread.sleep(1500)
                for (index in 0 until if (expanded) 40 else 20) {
                    if (expanded) {
                        val top = page.component.getLocationOnScreen().y
                        val bottom = top + page.component.height
                        driver.ui.xx { byText("Show details") }.list().filter { button ->
                            val y = button.component.getLocationOnScreen().y
                            y > top && y + button.component.height < bottom
                        }.forEach { it.click() }
                    }
                    Thread.sleep(1000)
                    val state = if (expanded) "details" else "overview"
                    val name = "$state-%02d".format(index)
                    val screenshot = driver.takeScreenshot("whatsnew-022-$name") ?: error("Missing page capture $name")
                    Files.copy(Path.of(screenshot.toString()), output.resolve("$name.png"), StandardCopyOption.REPLACE_EXISTING)
                    Files.writeString(output.resolve("$name.txt"), page.getAllTexts().joinToString("\n"))
                    page.robot.rotateMouseWheel(page.component, 4)
                }
            }
        }
    }

    @Test
    fun captureManagedAssembly() {
        val project = Files.createTempDirectory("hexana-022-managed-")
        for (name in listOf("sample-clr-portable.exe", "sample-clr-portable.pdb")) {
            Files.copy(repoRoot().resolve("parsing/native-fixtures/test-data/pe/$name"), project.resolve(name))
        }
        hexanaIde("hexana-022-managed", projectDir = project, configure = { showWhatsNewOnNewVersion(false) }) {
            maximizeWindow()
            closeProjectToolWindow()
            closeServicesToolWindow()
            val editor = openFile("sample-clr-portable.exe")
            val deadline = System.nanoTime() + 120.seconds.inWholeNanoseconds
            while ("IL Disassembly" !in editor.snapshot().panelNames) {
                check(System.nanoTime() < deadline) { "Managed tabs unavailable: ${editor.snapshot()}" }
                Thread.sleep(250)
            }
            editor.setTopFraction(0.15f)
            driver.ui.x { byText("IL Disassembly") }.waitFound().click()
            driver.ui.waitAny(timeout = 120.seconds) { contains(byText("Hexana.Sample.Greeter")) }.first().click()
            driver.ui.waitAny(timeout = 120.seconds) { contains(byText(".maxstack")) }
            clearNotifications()
            Thread.sleep(1500)
            copyScreenshot(driver, "managed-il")
        }
    }

    @Test
    fun captureApkClasses() {
        val project = Files.createTempDirectory("hexana-022-apk-")
        ZipOutputStream(Files.newOutputStream(project.resolve("sample-multidex.apk"))).use { zip ->
            for (name in listOf("classes.dex", "classes2.dex")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(buildWhatsNew022Dex())
                zip.closeEntry()
            }
        }
        hexanaIde("hexana-022-apk", projectDir = project, configure = { showWhatsNewOnNewVersion(false) }) {
            maximizeWindow()
            closeProjectToolWindow()
            closeServicesToolWindow()
            val editor = openFile("sample-multidex.apk")
            val deadline = System.nanoTime() + 120.seconds.inWholeNanoseconds
            while ("Classes" !in editor.snapshot().panelNames) {
                check(System.nanoTime() < deadline) { "APK Classes unavailable: ${editor.snapshot()}" }
                Thread.sleep(250)
            }
            editor.setTopFraction(0.15f)
            driver.ui.x { byText("Classes") }.waitFound().click()
            driver.ui.waitAny(timeout = 120.seconds) { contains(byText("Lcom/example/Foo;")) }
            driver.ui.waitAny(timeout = 120.seconds) { contains(byText("classes2.dex")) }
            clearNotifications()
            Thread.sleep(1500)
            copyScreenshot(driver, "apk-classes")
        }
    }

    @Test
    fun captureHttpReplay() {
        val project = Files.createTempDirectory("hexana-022-http-")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/release") { exchange ->
            val body = "{\"release\": \"0.22\", \"status\": \"replayed\"}".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            Files.write(project.resolve("requests.pcapng"), pcapNgFixture(server.address.port))
            hexanaIde("hexana-022-http", projectDir = project, configure = { showWhatsNewOnNewVersion(false) }) {
                maximizeWindow()
                closeProjectToolWindow()
                closeServicesToolWindow()
                val editor = openFile("requests.pcapng")
                val deadline = System.nanoTime() + 120.seconds.inWholeNanoseconds
                while ("Packets" !in editor.snapshot().panelNames) {
                    check(System.nanoTime() < deadline) { "Packets unavailable: ${editor.snapshot()}" }
                    Thread.sleep(250)
                }
                editor.setTopFraction(0.15f)
                driver.ui.x { byText("Packets") }.waitFound().click()
                driver.ui.x { contains(byText("GET /release")) }.waitFound(120.seconds).rightClick()
                driver.ui.x { byText("Replay HTTP Request…") }.waitFound(30.seconds).click()
                driver.ui.x("//div[contains(@title, 'Replay HTTP Request')]").waitFound(30.seconds)
                driver.ui.x("//div[@class='JBTextField' and @visible_text='http://127.0.0.1:${server.address.port}/release']").waitFound(30.seconds)
                driver.ui.x { byText("Send") }.waitFound(30.seconds).click()
                driver.ui.x { and(byClass("JLabel"), contains(byText("HTTP/1.1 200"))) }.waitFound(60.seconds)
                driver.ui.x { and(byClass("JBTextArea"), contains(byVisibleText("replayed"))) }.waitFound(60.seconds)
                clearNotifications()
                Thread.sleep(1500)
                copyScreenshot(driver, "http-replay")
            }
        } finally { server.stop(0) }
    }

    private fun pcapNgFixture(port: Int): ByteArray =
        sectionHeaderBlock() + interfaceDescriptionBlock() + listOf(
            ethernetIpv4TcpPacket(
                source = intArrayOf(10, 0, 0, 10),
                destination = intArrayOf(10, 0, 0, 20),
                sourcePort = 49_152,
                destinationPort = 80,
                payload = "GET /release HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".encodeToByteArray(),
            ),
            ethernetIpv4TcpPacket(
                source = intArrayOf(10, 0, 0, 20),
                destination = intArrayOf(10, 0, 0, 10),
                sourcePort = 80,
                destinationPort = 49_152,
                payload = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 14\r\n\r\nHello, Hexana!".encodeToByteArray(),
            ),
            ethernetIpv4TcpPacket(
                source = intArrayOf(10, 0, 0, 10),
                destination = intArrayOf(10, 0, 0, 20),
                sourcePort = 49_153,
                destinationPort = 80,
                payload = "GET /packets HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".encodeToByteArray(),
            ),
            ethernetIpv4TcpPacket(
                source = intArrayOf(10, 0, 0, 20),
                destination = intArrayOf(10, 0, 0, 10),
                sourcePort = 80,
                destinationPort = 49_153,
                payload = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".encodeToByteArray(),
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

    private fun copyScreenshot(driver: Driver, name: String) {
        val screenshot = driver.takeScreenshot("whatsnew-022-$name") ?: error("Missing screenshot: $name")
        val target = repoRoot().resolve("docs/whatsnew/captures/0.22").createDirectories().resolve("$name.png")
        Files.copy(Path.of(screenshot.toString()), target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) path = path.parent ?: error("Missing repository root")
        return path
    }
}

/** Compact, valid DEX fixture with two methods so the capture shows resolved operands and instruction offsets. */
private fun buildWhatsNew022Dex(): ByteArray {
    val strings = listOf(
        "Lcom/example/Foo;",
        "Ljava/lang/Object;",
        "I",
        "V",
        "x",
        "bar",
        "Foo.java",
        "hello",
        "Ljava/lang/String;",
        "print",
    )
    val types = listOf(0, 1, 2, 3, 8)
    val typeFoo = 0
    val typeObject = 1
    val typeInt = 2
    val typeVoid = 3
    val typeString = 4

    val headerSize = 0x70
    val stringIdsOff = headerSize
    val typeIdsOff = stringIdsOff + strings.size * 4
    val protoIdsOff = typeIdsOff + types.size * 4
    val fieldIdsOff = protoIdsOff + DEX_PROTO_COUNT * 12
    val methodIdsOff = fieldIdsOff + DEX_FIELD_COUNT * 8
    val classDefsOff = methodIdsOff + DEX_METHOD_COUNT * 8
    val dataOff = classDefsOff + DEX_CLASS_DEF_COUNT * 32
    require(dataOff % 4 == 0) { "data section must start 4-byte aligned, got $dataOff" }

    val data = WhatsNew022DexBytes()
    fun dataPosition(): Int = dataOff + data.size

    val stringDataOffsets = strings.map { value ->
        val at = dataPosition()
        data.uleb128(value.length)
        data.bytes(value.encodeToByteArray())
        data.u1(0)
        at
    }

    data.align(4)
    val printParamsOff = dataPosition()
    data.u4(1).u2(typeString).u2(0)

    data.align(4)
    val barCodeOff = dataPosition()
    data.u2(2).u2(1).u2(0).u2(0).u4(0).u4(4)
        .bytes(codeUnits(0x1012, 0x1059, 0x0000, 0x000E))

    data.align(4)
    val printCodeOff = dataPosition()
    data.u2(3).u2(2).u2(2).u2(1).u4(0).u4(6)
        .bytes(codeUnits(0x001A, 0x0007, 0x206E, 0x0001, 0x0001, 0x000E))
        .u4(0).u2(3).u2(1)
        .uleb128(1)
        .sleb128(-1).uleb128(typeObject).uleb128(5).uleb128(5)

    val classDataOff = dataPosition()
    data.uleb128(0).uleb128(1).uleb128(1).uleb128(1)
        .uleb128(0).uleb128(DEX_ACC_PRIVATE)
        .uleb128(0).uleb128(DEX_ACC_PUBLIC).uleb128(barCodeOff)
        .uleb128(1).uleb128(DEX_ACC_PUBLIC).uleb128(printCodeOff)
    val dataBytes = data.build()

    val tables = WhatsNew022DexBytes()
    for (offset in stringDataOffsets) tables.u4(offset)
    for (stringIdx in types) tables.u4(stringIdx)
    tables.u4(3).u4(typeVoid).u4(0)
    tables.u4(3).u4(typeVoid).u4(printParamsOff)
    tables.u2(typeFoo).u2(typeInt).u4(4)
    tables.u2(typeFoo).u2(0).u4(5)
    tables.u2(typeFoo).u2(1).u4(9)
    tables.u4(typeFoo)
        .u4(DEX_ACC_PUBLIC or DEX_ACC_FINAL)
        .u4(typeObject)
        .u4(0).u4(6).u4(0).u4(classDataOff).u4(0)
    val tableBytes = tables.build()

    val fileSize = dataOff + dataBytes.size
    val header = WhatsNew022DexBytes()
        .bytes(byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00))
        .u4(0)
        .bytes(ByteArray(20))
        .u4(fileSize)
        .u4(headerSize)
        .u4(0x12345678L)
        .u4(0).u4(0).u4(0)
        .u4(strings.size).u4(stringIdsOff)
        .u4(types.size).u4(typeIdsOff)
        .u4(DEX_PROTO_COUNT).u4(protoIdsOff)
        .u4(DEX_FIELD_COUNT).u4(fieldIdsOff)
        .u4(DEX_METHOD_COUNT).u4(methodIdsOff)
        .u4(DEX_CLASS_DEF_COUNT).u4(classDefsOff)
        .u4(dataBytes.size).u4(dataOff)
        .build()
    require(header.size == headerSize) { "header is ${header.size} bytes, expected $headerSize" }
    return header + tableBytes + dataBytes
}

private const val DEX_PROTO_COUNT = 2
private const val DEX_FIELD_COUNT = 1
private const val DEX_METHOD_COUNT = 2
private const val DEX_CLASS_DEF_COUNT = 1
private const val DEX_ACC_PUBLIC = 0x0001
private const val DEX_ACC_PRIVATE = 0x0002
private const val DEX_ACC_FINAL = 0x0010

private fun codeUnits(vararg units: Int): ByteArray {
    val out = ByteArray(units.size * 2)
    units.forEachIndexed { index, unit ->
        out[index * 2] = (unit and 0xFF).toByte()
        out[index * 2 + 1] = ((unit shr 8) and 0xFF).toByte()
    }
    return out
}

private class WhatsNew022DexBytes {
    private val out = ArrayList<Byte>()

    val size: Int get() = out.size

    fun u1(value: Int): WhatsNew022DexBytes {
        out.add((value and 0xFF).toByte())
        return this
    }

    fun u2(value: Int): WhatsNew022DexBytes {
        u1(value)
        u1(value shr 8)
        return this
    }

    fun u4(value: Long): WhatsNew022DexBytes {
        u2((value and 0xFFFF).toInt())
        u2(((value shr 16) and 0xFFFF).toInt())
        return this
    }

    fun u4(value: Int): WhatsNew022DexBytes = u4(value.toLong() and 0xFFFFFFFFL)

    fun uleb128(value: Int): WhatsNew022DexBytes {
        var remaining = value
        do {
            val byte = remaining and 0x7F
            remaining = remaining ushr 7
            u1(if (remaining != 0) byte or 0x80 else byte)
        } while (remaining != 0)
        return this
    }

    fun sleb128(value: Int): WhatsNew022DexBytes {
        var remaining = value
        while (true) {
            val byte = remaining and 0x7F
            val signBitSet = (byte and 0x40) != 0
            remaining = remaining shr 7
            val done = (remaining == 0 && !signBitSet) || (remaining == -1 && signBitSet)
            u1(if (done) byte else byte or 0x80)
            if (done) break
        }
        return this
    }

    fun bytes(values: ByteArray): WhatsNew022DexBytes {
        out.addAll(values.toList())
        return this
    }

    fun align(alignment: Int): WhatsNew022DexBytes {
        while (out.size % alignment != 0) u1(0)
        return this
    }

    fun build(): ByteArray = out.toByteArray()
}
