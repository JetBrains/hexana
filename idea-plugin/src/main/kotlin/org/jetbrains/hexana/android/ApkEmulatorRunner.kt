package org.jetbrains.hexana.android

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.hexana.shared.AndroidEmulatorRun
import java.io.File

/**
 * A locally installed Android SDK with `adb` and the Android Emulator package (as installed by
 * Android Studio). No SDK provisioning — if either is missing, [detect] returns null and the Run
 * button stays hidden. AVDs are listed at click time ([listAvds]), so one created in Android
 * Studio's Device Manager after the APK was opened is still picked up.
 *
 * Paths, commands and output parsing are shared with the VS Code extension in [AndroidEmulatorRun].
 */
internal class AndroidSdk(val root: File, val adb: File, val emulator: File) {

    /** Newest `aapt2`/`aapt` under `build-tools`, used to read the APK's package name. */
    val aapt: File? by lazy {
        val versions = File(AndroidEmulatorRun.buildToolsDir(root.path)).list().orEmpty().toList()
        AndroidEmulatorRun.aaptCandidates(root.path, versions, SystemInfo.isWindows).map(::File).firstOrNull { it.canExecute() }
    }

    /** AVD names known to the emulator; it resolves `ANDROID_AVD_HOME`/`ANDROID_USER_HOME` itself. */
    fun listAvds(): List<String> =
        runTool(listOf(emulator.path) + AndroidEmulatorRun.listAvdsArgs, timeoutMs = 15_000)
            ?.let(AndroidEmulatorRun::parseAvdList).orEmpty()

    fun onlineEmulator(): String? =
        runTool(listOf(adb.path) + AndroidEmulatorRun.devicesArgs)?.let(AndroidEmulatorRun::parseOnlineEmulators)?.firstOrNull()

    companion object {
        fun detect(): AndroidSdk? {
            val root = AndroidEmulatorRun.sdkRootCandidates(System.getenv(), System.getProperty("user.home"))
                .firstOrNull { File(AndroidEmulatorRun.platformToolsDir(it)).isDirectory } ?: return null
            val adb = File(AndroidEmulatorRun.adbPath(root, SystemInfo.isWindows)).takeIf { it.canExecute() } ?: return null
            val emulator = File(AndroidEmulatorRun.emulatorPath(root, SystemInfo.isWindows)).takeIf { it.canExecute() } ?: return null
            return AndroidSdk(File(root), adb, emulator)
        }
    }
}

/**
 * Reuses a running emulator if there is one; otherwise lets the user pick an AVD (when there is
 * more than one) to boot. Install and launch run in the background.
 */
internal fun runApkOnEmulator(project: Project, apk: File, sdk: AndroidSdk) {
    ApplicationManager.getApplication().executeOnPooledThread {
        val running = sdk.onlineEmulator()
        if (running != null) {
            startRun(project, apk, sdk, EmulatorTarget.Running(running))
            return@executeOnPooledThread
        }
        val avds = sdk.listAvds()
        when (avds.size) {
            0 -> notify(project, AndroidEmulatorRun.noAvdMessage(apk.name), NotificationType.WARNING)
            1 -> startRun(project, apk, sdk, EmulatorTarget.Boot(avds.single()))
            else -> invokeLater {
                JBPopupFactory.getInstance().createPopupChooserBuilder(avds)
                    .setTitle("Run ${apk.name} on Emulator")
                    .setItemChosenCallback { startRun(project, apk, sdk, EmulatorTarget.Boot(it)) }
                    .createPopup()
                    .showCenteredInCurrentWindow(project)
            }
        }
    }
}

private sealed interface EmulatorTarget {
    val label: String

    data class Running(val serial: String) : EmulatorTarget {
        override val label get() = serial
    }

    data class Boot(val avd: String) : EmulatorTarget {
        override val label get() = avd
    }
}

private fun startRun(project: Project, apk: File, sdk: AndroidSdk, target: EmulatorTarget) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Running ${apk.name} on ${target.label}", true) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val message = runApk(indicator, apk, sdk, target)
                notify(project, message, NotificationType.INFORMATION)
            } catch (e: ApkRunException) {
                notify(project, e.message ?: "Failed to run ${apk.name}", NotificationType.ERROR)
            }
        }
    })
}

private class ApkRunException(message: String) : Exception(message)

private fun runApk(indicator: ProgressIndicator, apk: File, sdk: AndroidSdk, target: EmulatorTarget): String {
    val adb = sdk.adb.path

    val serial = when (target) {
        is EmulatorTarget.Running -> target.serial
        is EmulatorTarget.Boot -> {
            indicator.text = "Starting emulator ${target.avd}…"
            // Detached: the emulator outlives this task and the IDE run.
            ProcessBuilder(listOf(sdk.emulator.path) + AndroidEmulatorRun.bootArgs(target.avd))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            waitForBoot(indicator, adb)
        }
    }

    indicator.text = "Installing ${apk.name}…"
    val install = runTool(
        listOf(adb) + AndroidEmulatorRun.installArgs(serial, apk.path),
        timeoutMs = AndroidEmulatorRun.INSTALL_TIMEOUT_MS.toInt(),
    )
    if (install == null || !AndroidEmulatorRun.isInstallSuccess(install)) {
        throw ApkRunException(AndroidEmulatorRun.installFailedMessage(install))
    }

    val packageName = sdk.aapt?.let { aapt ->
        runTool(listOf(aapt.path) + AndroidEmulatorRun.packageNameArgs(aapt.path, apk.path))
            ?.let(AndroidEmulatorRun::parsePackageName)
    } ?: return AndroidEmulatorRun.unknownPackageMessage(apk.name, serial)

    indicator.text = "Launching $packageName…"
    val launch = runTool(listOf(adb) + AndroidEmulatorRun.launchArgs(serial, packageName))
    if (launch == null || !AndroidEmulatorRun.hasLauncherActivity(launch)) {
        return AndroidEmulatorRun.noLauncherMessage(packageName, serial)
    }
    return AndroidEmulatorRun.launchedMessage(packageName, serial)
}

private fun waitForBoot(indicator: ProgressIndicator, adb: String): String {
    val deadline = System.currentTimeMillis() + AndroidEmulatorRun.BOOT_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        indicator.checkCanceled()
        val serial = runTool(listOf(adb) + AndroidEmulatorRun.devicesArgs)
            ?.let(AndroidEmulatorRun::parseOnlineEmulators)?.firstOrNull()
        if (serial != null) {
            indicator.text = "Waiting for $serial to boot…"
            val booted = runTool(listOf(adb) + AndroidEmulatorRun.bootCompletedArgs(serial))
            if (booted != null && AndroidEmulatorRun.isBootCompleted(booted)) return serial
        }
        Thread.sleep(AndroidEmulatorRun.BOOT_POLL_INTERVAL_MS)
    }
    throw ApkRunException(AndroidEmulatorRun.BOOT_TIMEOUT_MESSAGE)
}

private fun runTool(command: List<String>, timeoutMs: Int = 30_000): String? = try {
    val output = CapturingProcessHandler(GeneralCommandLine(command)).runProcess(timeoutMs)
    if (output.isTimeout) null else output.stdout + output.stderr
} catch (_: Exception) {
    null
}

private fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("hexana")
        .createNotification(content, type)
        .notify(project)
}
