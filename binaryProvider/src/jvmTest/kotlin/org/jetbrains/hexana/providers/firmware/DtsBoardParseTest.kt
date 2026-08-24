package org.jetbrains.hexana.providers.firmware

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DtsBoardParseTest {

    // ---------------------------------------------------------------------------
    // Helpers — same fixture infrastructure as DtsRegionsTest
    // ---------------------------------------------------------------------------

    private val repoRoot: Path by lazy {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent ?: error("Hexana repo root not found above ${Path.of("").toAbsolutePath()}")
        }
        dir
    }

    private fun budgetFile(relativePath: String): String {
        System.getenv("HEXANA_ZEPHYR_CORPUS")?.trim()?.ifBlank { null }?.let {
            return Path.of(it).resolve("budget-samples/$relativePath").toAbsolutePath().normalize().toString()
        }
        return repoRoot
            .resolve("ui-driver-tests/testData/fixtures/zephyr-corpus/budget-samples/$relativePath")
            .toAbsolutePath().normalize().toString()
    }

    private fun readText(relativePath: String): String =
        File(budgetFile(relativePath)).also {
            assertTrue(it.exists(), "Fixture not found: $relativePath")
        }.readText()

    // ---------------------------------------------------------------------------
    // parseDtsRootCompatible
    // ---------------------------------------------------------------------------

    @Test
    fun rootCompatibleNrf52840dk() {
        val dtsText = readText("hello_world-nrf52840dk-Os/build/zephyr/zephyr.dts")
        val compat = parseDtsRootCompatible(dtsText)
        assertEquals(listOf("nordic,nrf52840-dk-nrf52840"), compat,
            "Expected exactly the nrf52840dk root compatible")
    }

    @Test
    fun rootCompatibleInlineDts() {
        // Minimal DTS with a root compatible property.
        val dts = """
            /dts-v1/;
            / {
                #address-cells = < 0x1 >;
                compatible = "nordic,nrf52840-dk-nrf52840";
                chosen {
                    zephyr,console = &uart0;
                };
            };
        """.trimIndent()
        val compat = parseDtsRootCompatible(dts)
        assertEquals(listOf("nordic,nrf52840-dk-nrf52840"), compat)
    }

    @Test
    fun rootCompatibleMultipleTokens() {
        val dts = """
            /dts-v1/;
            / {
                compatible = "nordic,nrf52840-dk-nrf52840", "nordic,nrf52840";
            };
        """.trimIndent()
        val compat = parseDtsRootCompatible(dts)
        assertEquals(listOf("nordic,nrf52840-dk-nrf52840", "nordic,nrf52840"), compat)
    }

    @Test
    fun rootCompatibleAbsent() {
        val dts = """
            /dts-v1/;
            / {
                #address-cells = < 0x1 >;
            };
        """.trimIndent()
        val compat = parseDtsRootCompatible(dts)
        assertTrue(compat.isEmpty(), "Expected empty list when no root compatible")
    }

    @Test
    fun rootCompatibleNotPickedUpFromChildNode() {
        // A compatible inside a child node must NOT be returned as root compatible.
        val dts = """
            /dts-v1/;
            / {
                soc {
                    compatible = "simple-bus";
                };
            };
        """.trimIndent()
        val compat = parseDtsRootCompatible(dts)
        assertTrue(compat.isEmpty(), "Child-node compatible must not be returned as root compatible")
    }

    // ---------------------------------------------------------------------------
    // parseDtsCpuCompatible
    // ---------------------------------------------------------------------------

    @Test
    fun cpuCompatibleNrf52840dk() {
        val dtsText = readText("hello_world-nrf52840dk-Os/build/zephyr/zephyr.dts")
        val compat = parseDtsCpuCompatible(dtsText)
        assertTrue(compat.isNotEmpty(), "Expected at least one cpu compatible for nrf52840")
        assertTrue(compat.any { it.startsWith("arm,cortex-m") },
            "Expected an arm,cortex-m* compatible, got $compat")
    }

    @Test
    fun cpuCompatibleInlineCortexM4() {
        val dts = """
            /dts-v1/;
            / {
                cpus {
                    #address-cells = < 0x1 >;
                    #size-cells = < 0x0 >;
                    cpu@0 {
                        device_type = "cpu";
                        compatible = "arm,cortex-m4f";
                        reg = < 0x0 >;
                    };
                };
            };
        """.trimIndent()
        val compat = parseDtsCpuCompatible(dts)
        assertEquals(listOf("arm,cortex-m4f"), compat)
    }

    @Test
    fun cpuCompatibleInlineRiscV() {
        val dts = """
            /dts-v1/;
            / {
                cpus {
                    cpu@0 {
                        compatible = "riscv";
                        reg = < 0x0 >;
                    };
                };
            };
        """.trimIndent()
        val compat = parseDtsCpuCompatible(dts)
        assertEquals(listOf("riscv"), compat)
    }

    @Test
    fun cpuCompatibleAbsent() {
        val dts = """
            /dts-v1/;
            / {
                #address-cells = < 0x1 >;
            };
        """.trimIndent()
        val compat = parseDtsCpuCompatible(dts)
        assertTrue(compat.isEmpty(), "Expected empty list when no cpus block")
    }
}
