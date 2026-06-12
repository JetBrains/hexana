package org.jetbrains.hexana.ui.framework

import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.JCheckBoxUi
import com.intellij.driver.sdk.ui.components.elements.checkBox
import com.intellij.driver.sdk.ui.components.settings.SettingsDialogUiComponent
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.should
import kotlin.time.Duration.Companion.seconds

private const val TOOLS_SETTINGS_GROUP = "Tools"
private const val HEXANA_SETTINGS_PAGE = "Hexana"
private const val WASM_TOOLS_LABEL = "Wasm-tools:"
private const val LLVM_OBJDUMP_LABEL = "LLVM objdump:"
private const val USE_BUILT_IN_WASM_TOOLS_CHECKBOX = "Use Built-in wasm tools"
private const val SHOW_OFFSETS_CHECKBOX = "Show offsets"
private const val SEND_ANALYTICS_CHECKBOX = "Send anonymous usage analytics"

internal fun HexanaIde.hexanaSettings(action: HexanaSettingsDsl.() -> Unit) {
    driver.ideFrame {
        val settings = HexanaSettingsDsl(this)
        try {
            settings.action()
        } finally {
            settings.close()
        }
    }
}

internal class HexanaSettingsDsl internal constructor(
    private val ideFrame: IdeaFrameUI,
) {
    private var dialog: SettingsDialogUiComponent = ideFrame.openHexanaSettingsDialog()

    fun expectControlsPresent() {
        dialog.content {
            waitOneText(WASM_TOOLS_LABEL, timeout = 30.seconds)
            waitOneText(LLVM_OBJDUMP_LABEL, timeout = 30.seconds)
        }
        checkBox(USE_BUILT_IN_WASM_TOOLS_CHECKBOX).waitFound(30.seconds)
        checkBox(SHOW_OFFSETS_CHECKBOX).waitFound(30.seconds)
        checkBox(SEND_ANALYTICS_CHECKBOX).waitFound(30.seconds)
    }

    internal fun close() {
        runCatching {
            dialog.closeDialog()
        }
    }

    private fun checkBox(text: String): JCheckBoxUi =
        dialog.content().checkBox { byText(text) }.waitFound(30.seconds)
}

private fun IdeaFrameUI.openHexanaSettingsDialog(): SettingsDialogUiComponent {
    openSettingsDialog()
    return settingsDialog()
        .waitFound(30.seconds)
        .apply {
            settingsTree.should(
                message = "Settings tree misses $TOOLS_SETTINGS_GROUP | $HEXANA_SETTINGS_PAGE",
                timeout = 30.seconds,
            ) {
                pathExists(TOOLS_SETTINGS_GROUP, HEXANA_SETTINGS_PAGE)
            }
            openTreeSettingsSection(TOOLS_SETTINGS_GROUP, HEXANA_SETTINGS_PAGE)
            content().waitOneText(WASM_TOOLS_LABEL, timeout = 30.seconds)
        }
}
