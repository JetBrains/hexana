package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.structureToolWindow
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

@Tag("screenshot")
class HexanaWhatsNewCaptureTest {
    @Test
    fun captureFeatureScreenshots() {
        hexanaIde(
            testName = "hexana-whats-new-feature-capture",
            configure = {
                showWhatsNewOnNewVersion(false)
            },
        ) {
            closeProjectToolWindow()

            val fixtures = prepareWhatsNew013Fixtures()

            val structureEditor = openWasm()
            driver.ideFrame {
                editorTabs {
                    tab(structureEditor.fileName, fullMatch = false).waitFound(30.seconds)
                }
            }
            activateStructureToolWindow(structureEditor)
            driver.ideFrame {
                structureToolWindow().waitFound(30.seconds)
            }
            Thread.sleep(2.seconds.inWholeMilliseconds)
            copyScreenshot(driver, "structure-tool-window", "structure-tool-window.png")
            closeStructureToolWindow()

            val parquetEditor = openAbsoluteFile(fixtures.parquetFile)
            driver.ideFrame {
                editorTabs {
                    tab(fixtures.parquetFile.fileName.toString(), fullMatch = false).waitFound(30.seconds)
                }
            }
            parquetEditor.selectTab("Data")
            waitForSelectedTab(parquetEditor, "Data")
            Thread.sleep(1.seconds.inWholeMilliseconds)
            copyScreenshot(driver, "parquet-data", "parquet-data.png")

            val protobufEditor = openAbsoluteFile(fixtures.protobufDescriptorFile)
            driver.ideFrame {
                editorTabs {
                    tab(fixtures.protobufDescriptorFile.fileName.toString(), fullMatch = false).waitFound(30.seconds)
                }
            }
            protobufEditor.selectTab("Services")
            waitForSelectedTab(protobufEditor, "Services")
            Thread.sleep(1.seconds.inWholeMilliseconds)
            copyScreenshot(driver, "protobuf-descriptor", "protobuf-descriptor.png")

            val scriptEditor = openAbsoluteFileWithScriptTab(fixtures.scriptedParquetFile, fixtures.scriptFile)
            driver.ideFrame {
                editorTabs {
                    tab(fixtures.scriptedParquetFile.fileName.toString(), fullMatch = false).waitFound(30.seconds)
                }
            }
            scriptEditor.selectTab(fixtures.scriptFile.fileName.toString())
            waitForSelectedTab(scriptEditor, fixtures.scriptFile.fileName.toString())
            Thread.sleep(1.seconds.inWholeMilliseconds)
            copyScreenshot(driver, "scriptable-tabs", "scriptable-tabs.png")
        }
    }

    private fun waitForSelectedTab(
        editor: org.jetbrains.hexana.ui.framework.HexanaEditor,
        tabName: String,
    ) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastSelected: String? = null
        while (System.nanoTime() < deadline) {
            lastSelected = editor.snapshot().selectedTabName
            if (lastSelected == tabName) return
            Thread.sleep(250)
        }
        error("Timed out waiting for selected Hexana tab '$tabName'; last selected tab was '$lastSelected'")
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }

        val target = repoRoot()
            .resolve("docs/whatsnew/captures/0.13")
            .createDirectories()
            .resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New feature screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
        }
        return path
    }

    private fun prepareWhatsNew013Fixtures(): WhatsNew013Fixtures {
        val dir = Files.createTempDirectory("hexana-whatsnew-013-")
        val parquetFixture = repoRoot().resolve("plugins-shared/src/jvmTest/resources/alltypes_plain.parquet")
        require(parquetFixture.exists()) { "Missing Parquet fixture: $parquetFixture" }

        val parquetFile = dir.resolve("columnar-orders.parquet")
        val scriptedParquetFile = dir.resolve("scripted-columnar.parquet")
        Files.copy(parquetFixture, parquetFile, StandardCopyOption.REPLACE_EXISTING)
        Files.copy(parquetFixture, scriptedParquetFile, StandardCopyOption.REPLACE_EXISTING)

        val protobufDescriptorFile = dir.resolve("telemetry.protoset")
        protobufDescriptorFile.writeBytes(buildProtobufDescriptorSetFixture())

        val scriptFile = dir.resolve("hexana-summary.js")
        scriptFile.writeText(
            """
            print("metric,value")
            print("format,custom script")
            print("binaryAvailable," + (binary != null))
            print("rows,3")
            """.trimIndent(),
        )

        return WhatsNew013Fixtures(
            parquetFile = parquetFile,
            protobufDescriptorFile = protobufDescriptorFile,
            scriptedParquetFile = scriptedParquetFile,
            scriptFile = scriptFile,
        )
    }

    private fun buildProtobufDescriptorSetFixture(): ByteArray {
        val enumUnknown = buildProtoMessage {
            lenField(1, "UNKNOWN".encodeToByteArray())
            varintField(2, 0L)
        }
        val enumOk = buildProtoMessage {
            lenField(1, "OK".encodeToByteArray())
            varintField(2, 1L)
        }
        val enumError = buildProtoMessage {
            lenField(1, "ERROR".encodeToByteArray())
            varintField(2, 2L)
        }
        val statusEnum = buildProtoMessage {
            lenField(1, "Status".encodeToByteArray())
            lenField(2, enumUnknown)
            lenField(2, enumOk)
            lenField(2, enumError)
        }

        val fieldId = buildProtoMessage {
            lenField(1, "id".encodeToByteArray())
            varintField(3, 1L)
            varintField(4, 1L)
            varintField(5, 9L)
        }
        val fieldPayload = buildProtoMessage {
            lenField(1, "payload".encodeToByteArray())
            varintField(3, 2L)
            varintField(4, 1L)
            varintField(5, 9L)
        }
        val msgRequest = buildProtoMessage {
            lenField(1, "Request".encodeToByteArray())
            lenField(2, fieldId)
            lenField(2, fieldPayload)
        }

        val fieldStatus = buildProtoMessage {
            lenField(1, "status".encodeToByteArray())
            varintField(3, 1L)
            varintField(4, 1L)
            varintField(5, 14L)
            lenField(6, ".telemetry.Status".encodeToByteArray())
        }
        val msgResponse = buildProtoMessage {
            lenField(1, "Response".encodeToByteArray())
            lenField(2, fieldStatus)
        }

        val method = buildProtoMessage {
            lenField(1, "Report".encodeToByteArray())
            lenField(2, ".telemetry.Request".encodeToByteArray())
            lenField(3, ".telemetry.Response".encodeToByteArray())
        }
        val service = buildProtoMessage {
            lenField(1, "TelemetryService".encodeToByteArray())
            lenField(2, method)
        }

        val fileProto = buildProtoMessage {
            lenField(1, "telemetry.proto".encodeToByteArray())
            lenField(2, "telemetry".encodeToByteArray())
            lenField(4, msgRequest)
            lenField(4, msgResponse)
            lenField(5, statusEnum)
            lenField(6, service)
            lenField(12, "proto3".encodeToByteArray())
        }

        return buildProtoMessage { lenField(1, fileProto) }
    }

    private fun buildProtoMessage(block: ProtoBuilder.() -> Unit): ByteArray {
        val builder = ProtoBuilder()
        builder.block()
        return builder.build()
    }

    private data class WhatsNew013Fixtures(
        val parquetFile: Path,
        val protobufDescriptorFile: Path,
        val scriptedParquetFile: Path,
        val scriptFile: Path,
    )

    private class ProtoBuilder {
        private val bytes = mutableListOf<Byte>()

        fun varintField(fieldNumber: Int, value: Long) {
            writeTag(fieldNumber, 0)
            writeVarint(value)
        }

        fun lenField(fieldNumber: Int, value: ByteArray) {
            writeTag(fieldNumber, 2)
            writeVarint(value.size.toLong())
            value.forEach { bytes += it }
        }

        private fun writeTag(fieldNumber: Int, wireType: Int) {
            writeVarint(((fieldNumber shl 3) or wireType).toLong())
        }

        private fun writeVarint(value: Long) {
            var remaining = value
            do {
                var byte = (remaining and 0x7F).toInt()
                remaining = remaining ushr 7
                if (remaining != 0L) byte = byte or 0x80
                bytes += byte.toByte()
            } while (remaining != 0L)
        }

        fun build(): ByteArray = bytes.toByteArray()
    }
}
