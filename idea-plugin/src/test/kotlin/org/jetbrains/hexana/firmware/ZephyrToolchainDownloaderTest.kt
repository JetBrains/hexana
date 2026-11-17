package org.jetbrains.hexana.firmware

import org.jetbrains.hexana.providers.firmware.ArchClass
import org.jetbrains.hexana.providers.firmware.TargetProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [ZephyrToolchainDownloader].
 *
 * No network, no IDE, no process I/O — all tested functions are pure or work on
 * temp directories.
 */
class ZephyrToolchainDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // -------------------------------------------------------------------------
    // toolchainTargetFor — all archClass × elfIs64Bit combos
    // -------------------------------------------------------------------------

    private fun profile(arch: ArchClass, is64Bit: Boolean): TargetProfile =
        TargetProfile(
            archClass = arch,
            boardId = null,
            socCompatibles = emptyList(),
            elfIs64Bit = is64Bit,
            elfMachine = 0,
            confidence = org.jetbrains.hexana.providers.firmware.Confidence.ElfOnly,
        )

    @Test fun toolchainTarget_CortexM_32bit() {
        assertEquals("arm-zephyr-eabi", ZephyrToolchainDownloader.toolchainTargetFor(profile(ArchClass.CortexM, false)))
    }

    @Test fun toolchainTarget_CortexM_64bit_treatedAsArm() {
        // CortexM is always arm-zephyr-eabi regardless of ELF class (defensive)
        assertEquals("arm-zephyr-eabi", ZephyrToolchainDownloader.toolchainTargetFor(profile(ArchClass.CortexM, true)))
    }

    @Test fun toolchainTarget_CortexA_64bit() {
        assertEquals("aarch64-zephyr-elf", ZephyrToolchainDownloader.toolchainTargetFor(profile(ArchClass.CortexA, true)))
    }

    @Test fun toolchainTarget_CortexA_32bit() {
        assertEquals("arm-zephyr-eabi", ZephyrToolchainDownloader.toolchainTargetFor(profile(ArchClass.CortexA, false)))
    }

    @Test fun toolchainTarget_RiscV_32bit() {
        assertEquals("riscv64-zephyr-elf", ZephyrToolchainDownloader.toolchainTargetFor(profile(ArchClass.RiscV, false)))
    }

    @Test fun toolchainTarget_RiscV_64bit() {
        assertEquals("riscv64-zephyr-elf", ZephyrToolchainDownloader.toolchainTargetFor(profile(ArchClass.RiscV, true)))
    }

    @Test fun toolchainTarget_Other_returnsNull() {
        assertNull(ZephyrToolchainDownloader.toolchainTargetFor(profile(ArchClass.Other, false)))
    }

    @Test fun toolchainTarget_Other_64bit_returnsNull() {
        assertNull(ZephyrToolchainDownloader.toolchainTargetFor(profile(ArchClass.Other, true)))
    }

    // -------------------------------------------------------------------------
    // downloadUrlFor — golden URLs for specific host/target combos
    // -------------------------------------------------------------------------

    private fun url(target: String, isMac: Boolean, isLinux: Boolean, arch: String) =
        ZephyrToolchainDownloader.downloadUrlFor(target, isMac = isMac, isLinux = isLinux, hostArch = arch)

    @Test fun downloadUrl_mac_aarch64_arm() {
        assertEquals(
            "https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/" +
                "toolchain_gnu_macos-aarch64_arm-zephyr-eabi.tar.xz",
            url("arm-zephyr-eabi", isMac = true, isLinux = false, arch = "aarch64"),
        )
    }

    @Test fun downloadUrl_mac_aarch64_aarch64() {
        assertEquals(
            "https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/" +
                "toolchain_gnu_macos-aarch64_aarch64-zephyr-elf.tar.xz",
            url("aarch64-zephyr-elf", isMac = true, isLinux = false, arch = "aarch64"),
        )
    }

    @Test fun downloadUrl_mac_aarch64_riscv64() {
        assertEquals(
            "https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/" +
                "toolchain_gnu_macos-aarch64_riscv64-zephyr-elf.tar.xz",
            url("riscv64-zephyr-elf", isMac = true, isLinux = false, arch = "aarch64"),
        )
    }

    @Test fun downloadUrl_linux_x86_64_arm() {
        assertEquals(
            "https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/" +
                "toolchain_gnu_linux-x86_64_arm-zephyr-eabi.tar.xz",
            url("arm-zephyr-eabi", isMac = false, isLinux = true, arch = "amd64"),
        )
    }

    @Test fun downloadUrl_linux_aarch64_arm() {
        assertEquals(
            "https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/" +
                "toolchain_gnu_linux-aarch64_arm-zephyr-eabi.tar.xz",
            url("arm-zephyr-eabi", isMac = false, isLinux = true, arch = "aarch64"),
        )
    }

    @Test fun downloadUrl_windows_x86_64_arm_returnsNull() {
        // Windows .7z is not supported
        assertNull(url("arm-zephyr-eabi", isMac = false, isLinux = false, arch = "amd64"))
    }

    // -------------------------------------------------------------------------
    // locateGdbPy — flat layout, nested layout, SDK-root gnu/ layout, missing
    // -------------------------------------------------------------------------

    @Test fun locateGdbPy_flatLayout() {
        // <installDir>/bin/arm-zephyr-eabi-gdb-py
        val installDir = tmp.newFolder("flat-toolchain")
        val bin = File(installDir, "bin").also { it.mkdirs() }
        val gdb = File(bin, "arm-zephyr-eabi-gdb-py").also { it.createNewFile() }
        gdb.setExecutable(true)

        val result = ZephyrToolchainDownloader.locateGdbPy(installDir.toPath(), "arm-zephyr-eabi")
        assertEquals(gdb.absolutePath, result)
    }

    @Test fun locateGdbPy_nestedLayout() {
        // <installDir>/<target>/bin/arm-zephyr-eabi-gdb-py  (observed for arm in v1.0.1)
        val installDir = tmp.newFolder("nested-toolchain")
        val subDir = File(installDir, "arm-zephyr-eabi").also { it.mkdirs() }
        val bin = File(subDir, "bin").also { it.mkdirs() }
        val gdb = File(bin, "arm-zephyr-eabi-gdb-py").also { it.createNewFile() }
        gdb.setExecutable(true)

        val result = ZephyrToolchainDownloader.locateGdbPy(installDir.toPath(), "arm-zephyr-eabi")
        assertEquals(gdb.absolutePath, result)
    }

    @Test fun locateGdbPy_sdkRootGnuLayout() {
        // <sdkRoot>/gnu/<target>/bin/<target>-gdb-py  (full SDK root pointing at gnu/arm-zephyr-eabi)
        // In this test the "installDir" is the per-target dir (gnu/arm-zephyr-eabi).
        val sdkGnu = tmp.newFolder("sdk", "gnu", "arm-zephyr-eabi")
        val bin = File(sdkGnu, "bin").also { it.mkdirs() }
        val gdb = File(bin, "arm-zephyr-eabi-gdb-py").also { it.createNewFile() }
        gdb.setExecutable(true)

        // The locator is called on the per-target dir when addZephyrSdkGdbCandidates iterates gnu/
        val result = ZephyrToolchainDownloader.locateGdbPy(sdkGnu.toPath(), "arm-zephyr-eabi")
        assertEquals(gdb.absolutePath, result)
    }

    @Test fun locateGdbPy_noBinary_returnsNull() {
        val installDir = tmp.newFolder("empty-toolchain")
        File(installDir, "bin").mkdirs()
        // no gdb-py inside

        val result = ZephyrToolchainDownloader.locateGdbPy(installDir.toPath(), "arm-zephyr-eabi")
        assertNull(result)
    }

    @Test fun locateGdbPy_wrongBinaryName_returnsNull() {
        val installDir = tmp.newFolder("wrong-toolchain")
        val bin = File(installDir, "bin").also { it.mkdirs() }
        // Has gdb but not gdb-py
        File(bin, "arm-zephyr-eabi-gdb").also { it.createNewFile() }.setExecutable(true)

        val result = ZephyrToolchainDownloader.locateGdbPy(installDir.toPath(), "arm-zephyr-eabi")
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // parseDigestFromApiJson — basic extraction
    // -------------------------------------------------------------------------

    @Test fun parseDigest_findsKnownAsset() {
        val json = """
            {"assets":[
              {"name":"toolchain_gnu_macos-aarch64_arm-zephyr-eabi.tar.xz",
               "size":89958448,
               "digest":"sha256:4008edb5d4840cd994aedd7f1309bfb63e7243729d57839ebf1cc83c1f17c886"}
            ]}
        """.trimIndent()
        val digest = ZephyrToolchainDownloader.parseDigestFromApiJson(
            json, "toolchain_gnu_macos-aarch64_arm-zephyr-eabi.tar.xz"
        )
        assertEquals("4008edb5d4840cd994aedd7f1309bfb63e7243729d57839ebf1cc83c1f17c886", digest)
    }

    @Test fun parseDigest_unknownAsset_returnsNull() {
        val json = """{"assets":[{"name":"other.tar.xz","digest":"sha256:aabbcc"}]}"""
        assertNull(ZephyrToolchainDownloader.parseDigestFromApiJson(json, "missing.tar.xz"))
    }
}
