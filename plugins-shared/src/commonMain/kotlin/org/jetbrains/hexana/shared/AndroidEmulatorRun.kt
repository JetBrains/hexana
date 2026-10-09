package org.jetbrains.hexana.shared

/**
 * Pure logic for the trivial "Run APK on an existing Android emulator" action shared by the
 * IntelliJ plugin and the VS Code extension: where the SDK tools live, which commands to run,
 * and how to read their output. Each host owns process execution and orchestration (JVM runs it
 * synchronously in a background task, Node runs it asynchronously), so nothing here spawns
 * anything.
 *
 * Paths are plain `/`-joined strings so the logic compiles on JVM and JS; both hosts' file APIs
 * accept `/` on Windows.
 */
object AndroidEmulatorRun {

    const val BOOT_TIMEOUT_MS: Long = 300_000
    const val BOOT_POLL_INTERVAL_MS: Long = 2_000
    const val INSTALL_TIMEOUT_MS: Long = 180_000

    /**
     * SDK roots to probe, in order: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, then Android Studio's
     * default locations on macOS, Linux and Windows. The first one with executable `adb` and
     * `emulator` wins.
     */
    fun sdkRootCandidates(env: Map<String, String>, userHome: String?): List<String> = buildList {
        env["ANDROID_HOME"]?.takeIf { it.isNotBlank() }?.let(::add)
        env["ANDROID_SDK_ROOT"]?.takeIf { it.isNotBlank() }?.let(::add)
        if (!userHome.isNullOrBlank()) {
            add("$userHome/Library/Android/sdk")
            add("$userHome/Android/Sdk")
        }
        env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }?.let { add("$it/Android/Sdk") }
    }

    /** Directory whose presence marks [root] as an SDK root. */
    fun platformToolsDir(root: String): String = "$root/platform-tools"

    fun adbPath(root: String, isWindows: Boolean): String = "$root/platform-tools/${exe("adb", isWindows)}"

    fun emulatorPath(root: String, isWindows: Boolean): String = "$root/emulator/${exe("emulator", isWindows)}"

    fun buildToolsDir(root: String): String = "$root/build-tools"

    /**
     * `aapt2`/`aapt` candidates under [buildToolsVersions] (directory names of `build-tools`),
     * newest version first, `aapt2` before `aapt` within a version.
     */
    fun aaptCandidates(root: String, buildToolsVersions: List<String>, isWindows: Boolean): List<String> =
        buildToolsVersions.sortedWith(VersionComparator).reversed().flatMap { version ->
            listOf("aapt2", "aapt").map { "${buildToolsDir(root)}/$version/${exe(it, isWindows)}" }
        }

    // ── Commands (arguments after the executable) ────────────────────────────

    val listAvdsArgs: List<String> = listOf("-list-avds")

    fun bootArgs(avd: String): List<String> = listOf("-avd", avd)

    val devicesArgs: List<String> = listOf("devices")

    /** Asks the emulator console which AVD [serial] runs, so boot polling waits for the chosen one. */
    fun avdNameArgs(serial: String): List<String> = listOf("-s", serial, "emu", "avd", "name")

    fun bootCompletedArgs(serial: String): List<String> = listOf("-s", serial, "shell", "getprop", "sys.boot_completed")

    fun installArgs(serial: String, apkPath: String): List<String> = listOf("-s", serial, "install", "-r", "-t", apkPath)

    /** `aapt2 dump packagename` prints the bare name; old `aapt` only has `dump badging`. */
    fun packageNameArgs(aaptPath: String, apkPath: String): List<String> =
        if (aaptPath.substringAfterLast('/').startsWith("aapt2")) {
            listOf("dump", "packagename", apkPath)
        } else {
            listOf("dump", "badging", apkPath)
        }

    fun launchArgs(serial: String, packageName: String): List<String> =
        listOf("-s", serial, "shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")

    // ── Output parsing ───────────────────────────────────────────────────────

    /** `emulator -list-avds` prints one AVD name per line, possibly preceded by `INFO |` log noise. */
    fun parseAvdList(output: String): List<String> =
        output.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains('|') && !it.contains(' ') }

    /** Serials of online emulators from `adb devices` output. */
    fun parseOnlineEmulators(output: String): List<String> =
        output.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            parts.takeIf { it.size >= 2 && it[0].startsWith("emulator-") && it[1] == "device" }?.get(0)
        }

    /** `adb emu avd name` prints the AVD name followed by an `OK` line. */
    fun parseAvdName(output: String): String? =
        output.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "OK" }

    fun isBootCompleted(output: String): Boolean = output.trim() == "1"

    /** Package name from `aapt2 dump packagename` (bare name) or `aapt dump badging` (`package: name='…'`). */
    fun parsePackageName(output: String): String? {
        Regex("package: name='([^']+)'").find(output)?.let { return it.groupValues[1] }
        return output.lines().map { it.trim() }.firstOrNull { it.matches(Regex("[A-Za-z_][\\w.]*")) }
    }

    /** `adb install` prints `Success` on its own line; failure output may echo a path containing it. */
    fun isInstallSuccess(output: String): Boolean = output.lines().any { it.trim() == "Success" }

    /** `monkey` reports `Events injected: 1` only when it actually started the launcher activity. */
    fun isLaunchSuccess(monkeyOutput: String): Boolean = Regex("Events injected: [1-9]").containsMatchIn(monkeyOutput)

    fun hasLauncherActivity(monkeyOutput: String): Boolean = !monkeyOutput.contains("No activities found")

    // ── User-facing messages, so both hosts say the same thing ───────────────

    fun noAvdMessage(apkName: String): String =
        "No Android virtual device found. Create one in Android Studio's Device Manager, then run $apkName again."

    fun installFailedMessage(output: String?): String =
        "adb install failed: ${output?.trim()?.lines()?.lastOrNull()?.takeIf { it.isNotBlank() } ?: "no output"}"

    const val BOOT_TIMEOUT_MESSAGE: String = "Emulator did not finish booting within 5 minutes"

    fun unknownPackageMessage(apkName: String, serial: String): String =
        "Installed $apkName on $serial (package name unknown — launch it manually)"

    fun noLauncherMessage(packageName: String, serial: String): String =
        "Installed $packageName on $serial, but it has no launcher activity"

    fun launchFailedMessage(packageName: String, serial: String, output: String?): String =
        "Installed $packageName on $serial, but launching it failed: " +
            (output?.trim()?.lines()?.lastOrNull()?.takeIf { it.isNotBlank() } ?: "no output")

    fun launchedMessage(packageName: String, serial: String): String = "Launched $packageName on $serial"

    private fun exe(name: String, isWindows: Boolean) = if (isWindows) "$name.exe" else name

    /**
     * Orders `build-tools` directory names such as `30.0.3`, `36.1.0`, `37.0.0-rc1` numerically;
     * a stable release sorts after its prereleases (`37.0.0-rc1` < `37.0.0-rc2` < `37.0.0`).
     */
    private object VersionComparator : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val (baseA, qualifierA) = split(a)
            val (baseB, qualifierB) = split(b)
            for (i in 0 until maxOf(baseA.size, baseB.size)) {
                val c = baseA.getOrElse(i) { 0 }.compareTo(baseB.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return when {
                qualifierA == qualifierB -> 0
                qualifierA == null -> 1
                qualifierB == null -> -1
                else -> compareValuesBy(qualifierA, qualifierB, { it.trimEnd(Char::isDigit) }, { it.takeLastWhile(Char::isDigit).toIntOrNull() ?: 0 }, { it })
            }
        }

        private fun split(version: String): Pair<List<Int>, String?> {
            val base = version.substringBefore('-')
            val qualifier = version.substringAfter('-', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
            return base.split('.').map { it.toIntOrNull() ?: 0 } to qualifier
        }
    }
}
