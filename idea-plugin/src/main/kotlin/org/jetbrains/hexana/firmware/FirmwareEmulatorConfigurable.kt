package org.jetbrains.hexana.firmware

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.DslConfigurableBase
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.emptyText
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNonNullableProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.providers.firmware.QemuBinary
import org.jetbrains.hexana.providers.firmware.toCliName
import javax.swing.JLabel

/**
 * Settings page under **Build, Execution, Deployment | Firmware Emulator**.
 *
 * Lets the user configure QEMU system binary paths (one per architecture variant),
 * the Renode executable path, the dts2repl directory, and the download directory
 * for managed installs. Version detection is run off the EDT when the panel opens;
 * labels are updated via [invokeLater] once detection completes.
 *
 * Downloads (Renode, dts2repl, GDB toolchain) are offered in the Hexana Firmware
 * run configuration editor, not here.
 */
class FirmwareEmulatorConfigurable : DslConfigurableBase(), SearchableConfigurable {

    private val settings = HexanaFirmwareSettings.getInstance()

    // Version comment labels — populated off-EDT after panel open
    private var qemuArmLabel: JLabel? = null
    private var qemuAarch64Label: JLabel? = null
    private var qemuRiscv32Label: JLabel? = null
    private var qemuRiscv64Label: JLabel? = null
    private var renodeLabel: JLabel? = null
    private var gdbLabel: JLabel? = null

    override fun createPanel(): DialogPanel {
        val panel = panel {
            group("QEMU System Binaries") {
                qemuRow(QemuBinary.SystemArm, settings::qemuSystemArmPathInUi) { qemuArmLabel = it }
                qemuRow(QemuBinary.SystemAarch64, settings::qemuSystemAarch64PathInUi) { qemuAarch64Label = it }
                qemuRow(QemuBinary.SystemRiscv32, settings::qemuSystemRiscv32PathInUi) { qemuRiscv32Label = it }
                qemuRow(QemuBinary.SystemRiscv64, settings::qemuSystemRiscv64PathInUi) { qemuRiscv64Label = it }
            }
            group("Renode") {
                row("Renode path:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                        .align(AlignX.FILL)
                        .applyToComponent {
                            emptyText.text = settings.effectiveRenodesPath() ?: "auto-detect"
                            toolTipText = "Path to the Renode executable. " +
                                "Leave blank to auto-detect on PATH and standard locations."
                        }
                        .bindText(settings::renodePathInUi.toNonNullableProperty(""))
                }
                row {
                    val label = JLabel(versionStatus(EmulatorDetection.detectRenode()))
                    cell(label)
                    renodeLabel = label
                }.comment(
                    "Minimum version: ${RenodeDownloader.PINNED_RENODE_VERSION.split(".")[0]}." +
                        "${RenodeDownloader.PINNED_RENODE_VERSION.split(".")[1]}"
                )
            }
            group("GDB (DAP transport)") {
                row("Zephyr SDK path:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir())
                        .align(AlignX.FILL)
                        .applyToComponent {
                            emptyText.text = "e.g. ~/zephyr-sdk-1.0.1 (optional)"
                            toolTipText = "Root directory of a pre-installed Zephyr SDK " +
                                "(the directory containing gnu/<target>/bin). " +
                                "When set, GDB is probed here before \$ZEPHYR_SDK_INSTALL_DIR."
                        }
                        .bindText(settings::zephyrSdkPathInUi.toNonNullableProperty(""))
                }
                row("GDB path:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                        .align(AlignX.FILL)
                        .applyToComponent {
                            emptyText.text = EmulatorDetection.detectGdb()?.path ?: "auto-detect"
                            toolTipText = "Path to a GDB 14+ binary with Python support " +
                                "(arm-zephyr-eabi-gdb-py, arm-none-eabi-gdb-py, gdb-multiarch). " +
                                "Leave blank to auto-detect."
                        }
                        .bindText(settings::gdbPathInUi.toNonNullableProperty(""))
                }
                row {
                    val label = JLabel(versionStatus(null))
                    cell(label)
                    gdbLabel = label
                }.comment(
                    "Minimum version: 14 with Python. " +
                        "Zephyr SDK ships arm-zephyr-eabi-gdb-py; " +
                        "Arm GNU Toolchain ≥ 13.3.Rel1 ships arm-none-eabi-gdb-py."
                )
            }
            group("dts2repl") {
                row("dts2repl directory:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir())
                        .align(AlignX.FILL)
                        .applyToComponent {
                            emptyText.text = "auto-managed (acquire in run configuration)"
                            toolTipText = "Path to an existing dts2repl checkout or extracted directory. " +
                                "Leave blank to use the managed download."
                        }
                        .bindText(settings::dts2replPathInUi.toNonNullableProperty(""))
                }
                row {
                    comment("dts2repl converts Zephyr DTS files to Renode platform descriptions.")
                }
            }
            group("Download Directory") {
                row("Firmware tools directory:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir())
                        .align(AlignX.FILL)
                        .applyToComponent {
                            emptyText.text = settings.effectiveRenodeDownloadDir()
                            toolTipText = "Root directory for managed Renode and dts2repl downloads."
                        }
                        .bindText(settings::renodeDownloadDirInUi.toNonNullableProperty(""))
                }
            }
            row {
                comment(
                    "Downloads (Renode, dts2repl, GDB toolchain) are offered in the " +
                        "Hexana Firmware run configuration — open it via Run → Edit Configurations."
                )
            }
        }

        // Probe versions off-EDT after the panel is ready
        scheduleVersionProbe()

        return panel
    }

    private fun com.intellij.ui.dsl.builder.Panel.qemuRow(
        binary: QemuBinary,
        prop: kotlin.reflect.KMutableProperty0<String?>,
        labelCapture: (JLabel) -> Unit,
    ) {
        val label = "${binary.toCliName()} path:"
        row(label) {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                .align(AlignX.FILL)
                .applyToComponent {
                    emptyText.text = EmulatorDetection.detectQemu(binary)?.path ?: "auto-detect"
                    toolTipText = "Path to ${binary.toCliName()}. Leave blank to auto-detect."
                }
                .bindText(prop.toNonNullableProperty(""))
        }
        row {
            val versionLabel = JLabel(versionStatus(null))
            cell(versionLabel)
            labelCapture(versionLabel)
        }.comment("Minimum version: 7.0 (install via brew install qemu / apt install qemu-system-arm)")
    }

    private fun versionStatus(tool: EmulatorDetection.DetectedTool?): String = when {
        tool == null -> "Not detected"
        tool.version == null -> "Found at ${tool.path} (version unknown)"
        tool.meetsMinimum -> "Found ${tool.version} at ${tool.path}"
        else -> "Found ${tool.version} at ${tool.path} — below minimum version"
    }

    private fun scheduleVersionProbe() {
        CoroutineScope(Dispatchers.IO).launch {
            val qemuArm = EmulatorDetection.detectQemu(QemuBinary.SystemArm)
            val qemuAarch64 = EmulatorDetection.detectQemu(QemuBinary.SystemAarch64)
            val qemuRiscv32 = EmulatorDetection.detectQemu(QemuBinary.SystemRiscv32)
            val qemuRiscv64 = EmulatorDetection.detectQemu(QemuBinary.SystemRiscv64)
            val renode = EmulatorDetection.detectRenode()
            val gdb = EmulatorDetection.detectGdb()
            invokeLater {
                qemuArmLabel?.text = versionStatus(qemuArm)
                qemuAarch64Label?.text = versionStatus(qemuAarch64)
                qemuRiscv32Label?.text = versionStatus(qemuRiscv32)
                qemuRiscv64Label?.text = versionStatus(qemuRiscv64)
                renodeLabel?.text = versionStatus(renode)
                gdbLabel?.text = versionStatus(gdb)
            }
        }
    }

    override fun getId(): @NonNls String = "firmware.emulator.settings"

    override fun getDisplayName(): @NlsContexts.ConfigurableName String = "Firmware Emulator"
}
