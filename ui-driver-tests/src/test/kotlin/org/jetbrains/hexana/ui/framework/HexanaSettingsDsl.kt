package org.jetbrains.hexana.ui.framework

import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.JCheckBoxUi
import com.intellij.driver.sdk.ui.components.elements.checkBox
import com.intellij.driver.sdk.ui.components.elements.waitSelected
import com.intellij.driver.sdk.ui.components.settings.SettingsDialogUiComponent
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.should
import com.intellij.driver.sdk.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
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

    fun toggleShowOffsetsAndVerifyPersists() {
        val original = showOffsetsSelected()
        val changed = !original

        setShowOffsets(changed)
        apply()
        reopen()
        assertEquals(changed, showOffsetsSelected(), "Show offsets did not persist after applying Hexana settings")

        setShowOffsets(original)
        apply()
        reopen()
        assertEquals(original, showOffsetsSelected(), "Show offsets was not restored to its original state")
    }

    internal fun close() {
        runCatching {
            dialog.closeDialog()
        }
    }

    private fun reopen() {
        dialog.closeDialog()
        dialog = ideFrame.openHexanaSettingsDialog()
    }

    private fun showOffsetsSelected(): Boolean =
        checkBox(SHOW_OFFSETS_CHECKBOX).isSelected()

    private fun setShowOffsets(selected: Boolean) {
        val checkbox = checkBox(SHOW_OFFSETS_CHECKBOX)
        if (selected) {
            checkbox.check()
        } else {
            checkbox.uncheck()
        }
        checkbox.waitSelected(selected)
    }

    private fun apply() {
        waitFor("Hexana settings Apply button is enabled", timeout = 10.seconds) {
            dialog.applyButton.isEnabled()
        }
        dialog.applyButton.click()
        waitFor("Hexana settings Apply button is disabled", timeout = 10.seconds) {
            !dialog.applyButton.isEnabled()
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
