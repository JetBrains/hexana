package org.jetbrains.hexana.providers.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigBoardParseTest {

    @Test
    fun quotedBoardValue() {
        val config = """
            CONFIG_SOC_SERIES_NRF52X=y
            CONFIG_BOARD="qemu_cortex_m3"
            CONFIG_ARCH_HAS_SINGLE_THREAD_SUPPORT=y
        """.trimIndent()
        assertEquals("qemu_cortex_m3", parseConfigBoard(config))
    }

    @Test
    fun nrf52840Board() {
        val config = """
            CONFIG_BOARD_REVISION=""
            CONFIG_BOARD="nrf52840dk_nrf52840"
        """.trimIndent()
        assertEquals("nrf52840dk_nrf52840", parseConfigBoard(config))
    }

    @Test
    fun absentKey() {
        val config = "CONFIG_SOC_SERIES_NRF52X=y\nCONFIG_ARCH_HAS_SINGLE_THREAD_SUPPORT=y\n"
        assertNull(parseConfigBoard(config))
    }

    @Test
    fun emptyValue() {
        val config = "CONFIG_BOARD=\"\"\n"
        assertNull(parseConfigBoard(config))
    }

    @Test
    fun crlfLineEndings() {
        val config = "CONFIG_BOARD_REVISION=\"\"\r\nCONFIG_BOARD=\"mps2_an521\"\r\n"
        assertEquals("mps2_an521", parseConfigBoard(config))
    }

    @Test
    fun parseConfigValueGeneric() {
        val config = "CONFIG_BOARD=\"qemu_riscv32\"\nCONFIG_SOC=\"riscv\"\n"
        assertEquals("riscv", parseConfigValue(config, "CONFIG_SOC"))
        assertEquals("qemu_riscv32", parseConfigValue(config, "CONFIG_BOARD"))
        assertNull(parseConfigValue(config, "CONFIG_MISSING"))
    }

    @Test
    fun unquotedValue() {
        val config = "CONFIG_SOC=riscv\n"
        assertEquals("riscv", parseConfigValue(config, "CONFIG_SOC"))
    }
}
