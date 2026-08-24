package org.jetbrains.hexana.firmware.ui

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.hexana.firmware.Dts2ReplRunner
import org.jetbrains.hexana.firmware.EmulatorDetection
import org.jetbrains.hexana.firmware.FirmwareEmulatorConfigurable
import org.jetbrains.hexana.firmware.RenodeDownloader
import org.jetbrains.hexana.providers.firmware.Backend
import org.jetbrains.hexana.providers.firmware.QemuBinary
import org.jetbrains.hexana.providers.firmware.toCliName
import javax.swing.JComponent

/**
 * Popup dialog shown when the firmware Run/Debug button is clicked but
 * the required emulator tool is not available.
 *
 * Lists per-backend status: detected path + version (or "not found"), minimum-version
 * result, and action buttons to resolve missing tools.
 *
 * Call [show] on the EDT.
 */
class FirmwareAcquisitionPopup(
    private val project: Project?,
    private val requiredBackend: Backend,
    private val requiredQemuBinary: QemuBinary?,
) : DialogWrapper(project, /* canBeParent = */ true) {

    init {
        title = "Firmware Emulator — Tools Required"
        setOKButtonText("Close")
        isOKActionEnabled = true
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        when (requiredBackend) {
            Backend.Qemu -> buildQemuSection()
            Backend.Renode -> buildRenodeSection()
        }
        separator()
        group("Settings") {
            row {
                button("Open Firmware Emulator Settings…") {
                    close(OK_EXIT_CODE)
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, FirmwareEmulatorConfigurable::class.java)
                }
            }
        }
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
            row {
                button("Download Renode ${RenodeDownloader.PINNED_RENODE_VERSION}…") {
                    RenodeDownloader.downloadWithConsent(project) {
                        // refresh handled by the next button-enabled panel reopen
                    }
                }
            }
        }
        val dts2replInstalled = Dts2ReplRunner.isInstalled(Dts2ReplRunner.managedInstallDir()) ||
            org.jetbrains.hexana.firmware.HexanaFirmwareSettings.getInstance().effectiveDts2replPath() != null
        group("dts2repl (platform description generator)") {
            row("Status:") {
                label(if (dts2replInstalled) "Installed" else "Not installed").align(AlignX.FILL)
            }
            if (!dts2replInstalled) {
                row {
                    button("Fetch dts2repl (commit ${Dts2ReplRunner.PINNED_DTS2REPL_COMMIT.take(7)})…") {
                        Dts2ReplRunner.downloadWithConsent(project) { }
                    }
                }
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
         */
        fun show(
            project: Project?,
            requiredBackend: Backend,
            requiredQemuBinary: QemuBinary? = null,
        ) {
            FirmwareAcquisitionPopup(project, requiredBackend, requiredQemuBinary).show()
        }
    }
}
