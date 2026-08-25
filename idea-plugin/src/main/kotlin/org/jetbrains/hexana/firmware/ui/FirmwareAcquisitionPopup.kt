package org.jetbrains.hexana.firmware.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.hexana.firmware.Dts2ReplRunner
import org.jetbrains.hexana.firmware.EmulatorDetection
import org.jetbrains.hexana.providers.firmware.Backend
import org.jetbrains.hexana.providers.firmware.QemuBinary
import org.jetbrains.hexana.providers.firmware.toCliName
import javax.swing.JComponent

/**
 * Popup dialog shown when the firmware Run/Debug button is clicked but
 * the required emulator tool is not available.
 *
 * Shows per-tool status (detected path/version or "not found") and a single primary
 * action that opens the Hexana Firmware run configuration editor, where download
 * buttons for the missing tools are available.
 *
 * Call [show] on the EDT.
 */
class FirmwareAcquisitionPopup(
    private val project: Project?,
    private val requiredBackend: Backend,
    private val requiredQemuBinary: QemuBinary?,
    private val targetProfile: org.jetbrains.hexana.providers.firmware.TargetProfile? = null,
    private val elfFile: com.intellij.openapi.vfs.VirtualFile? = null,
) : DialogWrapper(project, /* canBeParent = */ true) {

    init {
        title = "Firmware Emulator — Tools Required"
        setOKButtonText("Open Run Configuration…")
        isOKActionEnabled = true
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        when (requiredBackend) {
            Backend.Qemu -> buildQemuSection()
            Backend.Renode -> buildRenodeSection()
        }
        buildGdbSection()
        separator()
        row {
            comment(
                "Use <b>Open Run Configuration…</b> to download missing tools and configure the emulator."
            )
        }
    }

    override fun doOKAction() {
        close(OK_EXIT_CODE)
        openRunConfiguration()
    }

    private fun openRunConfiguration() {
        val proj = project ?: return
        val runManager = com.intellij.execution.RunManager.getInstance(proj)
        val configurationType = com.intellij.execution.configurations.ConfigurationTypeUtil
            .findConfigurationType(
                org.jetbrains.hexana.firmware.run.FirmwareRunConfigurationType::class.java
            )
        val factory = configurationType.configurationFactories.first()

        // Reuse an existing config for this ELF, or create a new one.
        val settings = if (elfFile != null) {
            runManager.allSettings.firstOrNull { s ->
                s.type == configurationType &&
                    (s.configuration as? org.jetbrains.hexana.firmware.run.FirmwareRunConfiguration)
                        ?.elfPath == elfFile.path
            } ?: runManager.createConfiguration(elfFile.nameWithoutExtension, factory).also { newSettings ->
                (newSettings.configuration as org.jetbrains.hexana.firmware.run.FirmwareRunConfiguration).apply {
                    elfPath = elfFile.path
                }
                runManager.addConfiguration(newSettings)
            }
        } else {
            runManager.allSettings.firstOrNull { it.type == configurationType }
                ?: runManager.createConfiguration("Firmware", factory).also { runManager.addConfiguration(it) }
        }

        runManager.selectedConfiguration = settings
        com.intellij.execution.impl.RunDialog.editConfiguration(
            proj, settings, "Edit Firmware Run Configuration"
        )
    }

    private fun com.intellij.ui.dsl.builder.Panel.buildQemuSection() {
        val binary = requiredQemuBinary ?: QemuBinary.SystemArm
        val detected = EmulatorDetection.detectQemu(binary)
        group("QEMU — ${binary.toCliName()}") {
            row("Status:") {
                label(detectedStatus(detected, minVersion = "7.0")).align(AlignX.FILL)
            }
            row {
                text(
                    "QEMU is not bundled — install it via your package manager:\n" +
                        "  macOS:  <code>brew install qemu</code>\n" +
                        "  Debian: <code>sudo apt install qemu-system-arm qemu-system-misc</code>\n" +
                        "  Fedora: <code>sudo dnf install qemu-system-arm</code>"
                )
            }
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.buildRenodeSection() {
        val detected = EmulatorDetection.detectRenode()
        group("Renode") {
            row("Status:") {
                label(detectedStatus(detected, minVersion = "1.15")).align(AlignX.FILL)
            }
        }
        val dts2replInstalled = Dts2ReplRunner.isInstalled(Dts2ReplRunner.managedInstallDir()) ||
            org.jetbrains.hexana.firmware.HexanaFirmwareSettings.getInstance().effectiveDts2replPath() != null
        group("dts2repl (platform description generator)") {
            row("Status:") {
                label(if (dts2replInstalled) "Installed" else "Not installed").align(AlignX.FILL)
            }
            row {
                comment(
                    "dts2repl is required for targets without a built-in Renode platform description. " +
                        "It converts <code>zephyr.dts</code> to a Renode <code>.repl</code> file. " +
                        "Requires Python 3.9+ on PATH."
                )
            }
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.buildGdbSection() {
        val detected = EmulatorDetection.detectGdb()
        if (detected != null) return // GDB found; no action needed

        val toolchainTarget = targetProfile?.let {
            org.jetbrains.hexana.firmware.ZephyrToolchainDownloader.toolchainTargetFor(it)
        } ?: "arm-zephyr-eabi"

        group("GDB (DAP transport)") {
            row("Status:") {
                label("Not found — GDB 14+ with Python is required for debugging ($toolchainTarget)").align(AlignX.FILL)
            }
        }
    }

    private fun detectedStatus(
        tool: EmulatorDetection.DetectedTool?,
        minVersion: String,
    ): String = when {
        tool == null -> "Not found — install required (minimum $minVersion)"
        tool.version == null -> "Found at ${tool.path} (version could not be parsed)"
        tool.meetsMinimum -> "Found ${tool.version} at ${tool.path}"
        else -> "Found ${tool.version} at ${tool.path} — below minimum $minVersion"
    }

    override fun createActions() = arrayOf(okAction)

    companion object {
        /**
         * Creates and shows the popup on the EDT.
         *
         * @param project The current project (nullable for application-level context).
         * @param requiredBackend Which backend is needed ([Backend.Qemu] or [Backend.Renode]).
         * @param requiredQemuBinary The specific QEMU binary variant, when [Backend.Qemu] is required.
         * @param targetProfile Optional profile used to report the appropriate GDB toolchain.
         * @param elfFile Optional ELF file used to find or create the matching run configuration.
         */
        fun show(
            project: Project?,
            requiredBackend: Backend,
            requiredQemuBinary: QemuBinary? = null,
            targetProfile: org.jetbrains.hexana.providers.firmware.TargetProfile? = null,
            elfFile: com.intellij.openapi.vfs.VirtualFile? = null,
        ) {
            FirmwareAcquisitionPopup(project, requiredBackend, requiredQemuBinary, targetProfile, elfFile).show()
        }
    }
}
