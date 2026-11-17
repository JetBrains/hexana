package org.jetbrains.hexana.firmware.run

import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.icons.AllIcons

/**
 * Run configuration type for launching ELF firmware in an emulator (QEMU or Renode).
 */
class FirmwareRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Hexana Firmware",
    "Run ELF firmware under QEMU or Renode emulation",
    AllIcons.FileTypes.BinaryData,
) {
    init {
        addFactory(FirmwareConfigurationFactory(this))
    }

    companion object {
        const val ID = "HexanaFirmwareRunConfiguration"
    }
}
