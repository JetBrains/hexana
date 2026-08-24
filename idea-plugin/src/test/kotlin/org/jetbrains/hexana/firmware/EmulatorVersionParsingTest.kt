package org.jetbrains.hexana.firmware

import com.intellij.testFramework.UsefulTestCase

/**
 * Pure parser tests for version-string parsers in [EmulatorDetection].
 *
 * These tests run with no process I/O and no IntelliJ services — they exercise only
 * the top-level parsing functions and version-comparison helpers. Tests must be able
 * to fail: each case asserts the exact expected output; malformed inputs assert null.
 */
class EmulatorVersionParsingTest : UsefulTestCase() {

    // -------------------------------------------------------------------------
    // parseQemuVersion
    // -------------------------------------------------------------------------

    fun testParseQemuVersionHomebrew() {
        val line = "QEMU emulator version 9.2.2 (Homebrew)"
        assertEquals("9.2.2", EmulatorDetection.parseQemuVersion(line))
    }

    fun testParseQemuVersionDebian() {
        val line = "QEMU emulator version 8.2.0 (Debian 1:8.2.0+ds-1)"
        assertEquals("8.2.0", EmulatorDetection.parseQemuVersion(line))
    }

    fun testParseQemuVersionNoSuffix() {
        val line = "QEMU emulator version 7.0.0"
        assertEquals("7.0.0", EmulatorDetection.parseQemuVersion(line))
    }

    fun testParseQemuVersionMalformed() {
        assertNull(EmulatorDetection.parseQemuVersion(""))
        assertNull(EmulatorDetection.parseQemuVersion("bash: qemu-system-arm: command not found"))
        assertNull(EmulatorDetection.parseQemuVersion("No such file or directory"))
        assertNull(EmulatorDetection.parseQemuVersion("QEMU emulator version abc"))
    }

    // -------------------------------------------------------------------------
    // parseRenodeVersion
    // -------------------------------------------------------------------------

    fun testParseRenodeVersionCanonical() {
        // "Renode, version 1.16.1.12345 (commit abc...)" style
        val line = "Renode, version 1.16.1.67890 (commit cf9df05)"
        assertEquals("1.16.1", EmulatorDetection.parseRenodeVersion(line))
    }

    fun testParseRenodeVersionV() {
        val line = "Renode v1.16.1"
        assertEquals("1.16.1", EmulatorDetection.parseRenodeVersion(line))
    }

    fun testParseRenodeVersionOlderStyle() {
        val line = "Renode, version 1.15.0"
        assertEquals("1.15.0", EmulatorDetection.parseRenodeVersion(line))
    }

    fun testParseRenodeVersionMalformed() {
        assertNull(EmulatorDetection.parseRenodeVersion(""))
        assertNull(EmulatorDetection.parseRenodeVersion("bash: renode: command not found"))
    }

    // -------------------------------------------------------------------------
    // parseGdbVersion
    // -------------------------------------------------------------------------

    fun testParseGdbVersionArmToolchain() {
        val line = "GNU gdb (Arm GNU Toolchain 13.3.Rel1 (Build arm-13.24)) 14.2.90.20240531-git"
        val result = EmulatorDetection.parseGdbVersion(line)
        assertNotNull(result)
        assertEquals("Arm GNU Toolchain 13.3.Rel1 (Build arm-13.24)", result!!.first)
        assertEquals("14.2.90.20240531", result.second)
    }

    fun testParseGdbVersionUbuntu() {
        val line = "GNU gdb (Ubuntu 12.1-0ubuntu1~22.04) 12.1"
        val result = EmulatorDetection.parseGdbVersion(line)
        assertNotNull(result)
        assertEquals("Ubuntu 12.1-0ubuntu1~22.04", result!!.first)
        assertEquals("12.1", result.second)
    }

    fun testParseGdbVersionGnuArmEmbedded() {
        val line = "GNU gdb (GNU Arm Embedded Toolchain 10.3-2021.10) 10.2.90.20210621-git"
        val result = EmulatorDetection.parseGdbVersion(line)
        assertNotNull(result)
        assertEquals("GNU Arm Embedded Toolchain 10.3-2021.10", result!!.first)
        assertEquals("10.2.90.20210621", result.second)
    }

    fun testParseGdbVersionMalformed() {
        assertNull(EmulatorDetection.parseGdbVersion(""))
        assertNull(EmulatorDetection.parseGdbVersion("bash: gdb: command not found"))
        assertNull(EmulatorDetection.parseGdbVersion("gdb version 14"))
    }

    // -------------------------------------------------------------------------
    // parseLldbVersion
    // -------------------------------------------------------------------------

    fun testParseLldbVersionUpstream() {
        val line = "lldb version 17.0.6"
        assertEquals("17.0.6", EmulatorDetection.parseLldbVersion(line))
    }

    fun testParseLldbVersionApple() {
        val line = "lldb-1500.0.404.7"
        assertEquals("1500.0.404.7", EmulatorDetection.parseLldbVersion(line))
    }

    fun testParseLldbVersionMalformed() {
        assertNull(EmulatorDetection.parseLldbVersion(""))
        assertNull(EmulatorDetection.parseLldbVersion("bash: lldb: command not found"))
    }

    // -------------------------------------------------------------------------
    // compareVersions and minimum checks
    // -------------------------------------------------------------------------

    fun testCompareVersionsEqual() {
        assertEquals(0, EmulatorDetection.compareVersions("7.0.0", "7.0.0"))
        assertEquals(0, EmulatorDetection.compareVersions("1.16.1", "1.16.1"))
    }

    fun testCompareVersionsLess() {
        assertTrue(EmulatorDetection.compareVersions("6.9.0", "7.0.0") < 0)
        assertTrue(EmulatorDetection.compareVersions("1.14.9", "1.15.0") < 0)
    }

    fun testCompareVersionsGreater() {
        assertTrue(EmulatorDetection.compareVersions("9.2.2", "7.0") > 0)
        assertTrue(EmulatorDetection.compareVersions("1.16.1", "1.15.0") > 0)
    }

    fun testMeetsQemuMinimum() {
        assertTrue(EmulatorDetection.meetsQemuMinimum("7.0.0"))
        assertTrue(EmulatorDetection.meetsQemuMinimum("9.2.2"))
        assertFalse(EmulatorDetection.meetsQemuMinimum("6.9.0"))
    }

    fun testMeetsRenodeMinimum() {
        assertTrue(EmulatorDetection.meetsRenodeMinimum("1.15.0"))
        assertTrue(EmulatorDetection.meetsRenodeMinimum("1.16.1"))
        assertFalse(EmulatorDetection.meetsRenodeMinimum("1.14.9"))
        assertFalse(EmulatorDetection.meetsRenodeMinimum("1.0.0"))
    }

    fun testMeetsGdbMinimum() {
        assertTrue(EmulatorDetection.meetsGdbMinimum("14.0"))
        assertTrue(EmulatorDetection.meetsGdbMinimum("14.2.90.20240531"))
        assertFalse(EmulatorDetection.meetsGdbMinimum("13.2"))
        assertFalse(EmulatorDetection.meetsGdbMinimum("12.1"))
    }
}
