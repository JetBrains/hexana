package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidEmulatorRunTest {

    @Test
    fun parsesAvdListIgnoringLogNoise() {
        val output = "INFO    | Storing crashdata in: /tmp/foo\nPixel_8_API_35\nMedium_Phone\n\n"
        assertEquals(listOf("Pixel_8_API_35", "Medium_Phone"), AndroidEmulatorRun.parseAvdList(output))
        assertEquals(emptyList(), AndroidEmulatorRun.parseAvdList(""))
    }

    @Test
    fun parsesOnlineEmulatorsOnly() {
        val output = """
            List of devices attached
            emulator-5554	device
            emulator-5556	offline
            R58M12345	device
        """.trimIndent()
        assertEquals(listOf("emulator-5554"), AndroidEmulatorRun.parseOnlineEmulators(output))
    }

    @Test
    fun parsesPackageNameFromAapt2AndAapt() {
        assertEquals("com.example.app", AndroidEmulatorRun.parsePackageName("com.example.app\n"))
        assertEquals(
            "com.example.app",
            AndroidEmulatorRun.parsePackageName("package: name='com.example.app' versionCode='1' versionName='1.0'\nsdkVersion:'24'"),
        )
        assertNull(AndroidEmulatorRun.parsePackageName("W/ResourceType: bad\n"))
    }

    @Test
    fun sdkRootCandidatesPreferEnvironment() {
        val candidates = AndroidEmulatorRun.sdkRootCandidates(mapOf("ANDROID_HOME" to "/opt/sdk"), "/home/u")
        assertEquals(listOf("/opt/sdk", "/home/u/Library/Android/sdk", "/home/u/Android/Sdk"), candidates)
    }

    @Test
    fun aaptCandidatesAreNewestFirst() {
        val candidates = AndroidEmulatorRun.aaptCandidates("/sdk", listOf("30.0.3", "37.0.0", "36.1.0"), isWindows = false)
        assertEquals("/sdk/build-tools/37.0.0/aapt2", candidates.first())
        assertEquals("/sdk/build-tools/37.0.0/aapt", candidates[1])
        assertEquals("/sdk/build-tools/30.0.3/aapt", candidates.last())
    }

    @Test
    fun aaptCandidatesPreferStableOverPrerelease() {
        for (versions in listOf(listOf("37.0.0-rc1", "37.0.0", "37.0.0-rc2"), listOf("37.0.0", "37.0.0-rc2", "37.0.0-rc1"))) {
            val candidates = AndroidEmulatorRun.aaptCandidates("/sdk", versions, isWindows = false)
            assertEquals(
                listOf("37.0.0", "37.0.0-rc2", "37.0.0-rc1"),
                candidates.filter { it.endsWith("/aapt2") }.map { it.removePrefix("/sdk/build-tools/").removeSuffix("/aapt2") },
            )
        }
    }

    @Test
    fun parsesAvdName() {
        assertEquals("Pixel_8_API_35", AndroidEmulatorRun.parseAvdName("Pixel_8_API_35\r\nOK\r\n"))
        assertNull(AndroidEmulatorRun.parseAvdName("OK\n"))
    }

    @Test
    fun packageNameArgsDependOnTool() {
        assertEquals(listOf("dump", "packagename", "a.apk"), AndroidEmulatorRun.packageNameArgs("/x/aapt2.exe", "a.apk"))
        assertEquals(listOf("dump", "badging", "a.apk"), AndroidEmulatorRun.packageNameArgs("/x/aapt", "a.apk"))
    }

    @Test
    fun interpretsInstallAndLaunchOutput() {
        assertTrue(AndroidEmulatorRun.isInstallSuccess("Performing Streamed Install\nSuccess\n"))
        assertFalse(AndroidEmulatorRun.isInstallSuccess("Failure [INSTALL_FAILED_NO_MATCHING_ABIS]"))
        assertFalse(
            AndroidEmulatorRun.isInstallSuccess("adb: failed to install /tmp/Success.apk: Failure [INSTALL_FAILED_INVALID_APK]"),
        )
        assertFalse(AndroidEmulatorRun.hasLauncherActivity("** No activities found to run, monkey aborted."))
        assertTrue(AndroidEmulatorRun.isLaunchSuccess("  bash arg: -p\nEvents injected: 1\n## Network stats: elapsed time=12ms"))
        assertFalse(AndroidEmulatorRun.isLaunchSuccess("adb: device offline"))
        assertFalse(AndroidEmulatorRun.isLaunchSuccess("** No activities found to run, monkey aborted."))
        assertTrue(AndroidEmulatorRun.isBootCompleted("1\n"))
        assertEquals(
            "adb install failed: Failure [INSTALL_FAILED_NO_MATCHING_ABIS]",
            AndroidEmulatorRun.installFailedMessage("Performing Streamed Install\nFailure [INSTALL_FAILED_NO_MATCHING_ABIS]\n"),
        )
    }
}
