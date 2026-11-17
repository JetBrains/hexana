package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.seconds

@Tag("screenshot")
class HexanaWhatsNew020DexSmaliCaptureTest {
    @Test
    fun captureDexSmali() {
        val fixture = Files.createTempFile("hexana-whats-new-020-smali-", ".dex")
        fixture.writeBytes(buildSampleDex())

        hexanaIde(
            testName = "hexana-whats-new-020-dex-smali-capture",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()
            val editor = openAbsoluteFile(fixture)
            waitForSmali(editor)
            editor.selectTab(SMALI_TAB)
            waitForSelectedSmali(editor)
            copyScreenshot(driver, "dex-smali", "dex-smali.png")
        }
    }

    private fun waitForSmali(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 90.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.panelsResolved && SMALI_TAB in lastSnapshot.panelNames) return
            Thread.sleep(250)
        }
        error("Timed out waiting for the DEX Smali tab; snapshot=$lastSnapshot")
    }

    private fun waitForSelectedSmali(editor: HexanaEditor) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.selectedTabName == SMALI_TAB) return
            Thread.sleep(250)
        }
        error("Timed out waiting for the selected DEX Smali tab; snapshot=$lastSnapshot")
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }
        val target = repoRoot().resolve("docs/whatsnew/captures/0.20").createDirectories().resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.20 DEX Smali screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) path = path.parent ?: error("Could not locate repository root")
        return path
    }

    private companion object {
        const val SMALI_TAB = "Smali"
    }
}

/** Compact, valid DEX fixture with two methods so the capture shows resolved operands and instruction offsets. */
private fun buildSampleDex(): ByteArray {
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

    val data = DexBytes()
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

    val tables = DexBytes()
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
    val header = DexBytes()
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

private class DexBytes {
    private val out = ArrayList<Byte>()

    val size: Int get() = out.size

    fun u1(value: Int): DexBytes {
        out.add((value and 0xFF).toByte())
        return this
    }

    fun u2(value: Int): DexBytes {
        u1(value)
        u1(value shr 8)
        return this
    }

    fun u4(value: Long): DexBytes {
        u2((value and 0xFFFF).toInt())
        u2(((value shr 16) and 0xFFFF).toInt())
        return this
    }

    fun u4(value: Int): DexBytes = u4(value.toLong() and 0xFFFFFFFFL)

    fun uleb128(value: Int): DexBytes {
        var remaining = value
        do {
            val byte = remaining and 0x7F
            remaining = remaining ushr 7
            u1(if (remaining != 0) byte or 0x80 else byte)
        } while (remaining != 0)
        return this
    }

    fun sleb128(value: Int): DexBytes {
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

    fun bytes(values: ByteArray): DexBytes {
        out.addAll(values.toList())
        return this
    }

    fun align(alignment: Int): DexBytes {
        while (out.size % alignment != 0) u1(0)
        return this
    }

    fun build(): ByteArray = out.toByteArray()
}
