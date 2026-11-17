package org.jetbrains.hexana.android

import com.intellij.execution.ExecutionException
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
import java.io.IOException

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
        fun detect(): AndroidSdk? =
            AndroidEmulatorRun.sdkRootCandidates(System.getenv(), System.getProperty("user.home")).firstNotNullOfOrNull { root ->
                if (!File(AndroidEmulatorRun.platformToolsDir(root)).isDirectory) return@firstNotNullOfOrNull null
                val adb = File(AndroidEmulatorRun.adbPath(root, SystemInfo.isWindows)).takeIf { it.canExecute() }
                val emulator = File(AndroidEmulatorRun.emulatorPath(root, SystemInfo.isWindows)).takeIf { it.canExecute() }
                if (adb != null && emulator != null) AndroidSdk(File(root), adb, emulator) else null
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
            try {
                ProcessBuilder(listOf(sdk.emulator.path) + AndroidEmulatorRun.bootArgs(target.avd))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (e: IOException) {
                throw ApkRunException("Failed to start emulator ${target.avd}: ${e.message}")
            }
            waitForBoot(indicator, adb, target.avd)
        }
    }

    indicator.text = "Installing ${apk.name}…"
    val install = runTool(
        listOf(adb) + AndroidEmulatorRun.installArgs(serial, apk.path),
        timeoutMs = AndroidEmulatorRun.INSTALL_TIMEOUT_MS.toInt(),
        indicator = indicator,
    )
    if (install == null || !AndroidEmulatorRun.isInstallSuccess(install)) {
        throw ApkRunException(AndroidEmulatorRun.installFailedMessage(install))
    }

    val packageName = sdk.aapt?.let { aapt ->
        runTool(listOf(aapt.path) + AndroidEmulatorRun.packageNameArgs(aapt.path, apk.path), indicator = indicator)
            ?.let(AndroidEmulatorRun::parsePackageName)
    } ?: return AndroidEmulatorRun.unknownPackageMessage(apk.name, serial)

    indicator.text = "Launching $packageName…"
    val launch = runTool(listOf(adb) + AndroidEmulatorRun.launchArgs(serial, packageName), indicator = indicator)
    return when {
        launch != null && AndroidEmulatorRun.isLaunchSuccess(launch) -> AndroidEmulatorRun.launchedMessage(packageName, serial)
        launch != null && !AndroidEmulatorRun.hasLauncherActivity(launch) -> AndroidEmulatorRun.noLauncherMessage(packageName, serial)
        else -> throw ApkRunException(AndroidEmulatorRun.launchFailedMessage(packageName, serial, launch))
    }
}

/** Waits for the emulator running [avd] — not just any emulator that comes online meanwhile. */
private fun waitForBoot(indicator: ProgressIndicator, adb: String, avd: String): String {
    val deadline = System.currentTimeMillis() + AndroidEmulatorRun.BOOT_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        indicator.checkCanceled()
        val serial = runTool(listOf(adb) + AndroidEmulatorRun.devicesArgs, indicator = indicator)
            ?.let(AndroidEmulatorRun::parseOnlineEmulators).orEmpty()
            .firstOrNull { serial ->
                runTool(listOf(adb) + AndroidEmulatorRun.avdNameArgs(serial), indicator = indicator)
                    ?.let(AndroidEmulatorRun::parseAvdName) == avd
            }
        if (serial != null) {
            indicator.text = "Waiting for $serial to boot…"
            val booted = runTool(listOf(adb) + AndroidEmulatorRun.bootCompletedArgs(serial), indicator = indicator)
            if (booted != null && AndroidEmulatorRun.isBootCompleted(booted)) return serial
        }
        Thread.sleep(AndroidEmulatorRun.BOOT_POLL_INTERVAL_MS)
    }
    throw ApkRunException(AndroidEmulatorRun.BOOT_TIMEOUT_MESSAGE)
}

/**
 * Runs a tool and returns stdout+stderr, or null on start failure or timeout. With an [indicator],
 * cancelling it kills the process and throws [com.intellij.openapi.progress.ProcessCanceledException].
 */
private fun runTool(command: List<String>, timeoutMs: Int = 30_000, indicator: ProgressIndicator? = null): String? {
    val output = try {
        val handler = CapturingProcessHandler(GeneralCommandLine(command))
        if (indicator != null) handler.runProcessWithProgressIndicator(indicator, timeoutMs) else handler.runProcess(timeoutMs)
    } catch (_: ExecutionException) {
        return null
    }
    indicator?.checkCanceled()
    return if (output.isTimeout || output.isCancelled) null else output.stdout + output.stderr
}

private fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("hexana")
        .createNotification(content, type)
        .notify(project)
}
