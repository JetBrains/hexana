package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.JCheckBoxUi
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.checkBox
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.scrollBars
import com.intellij.driver.sdk.ui.components.settings.SettingsDialogUiComponent
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.should
import java.awt.event.KeyEvent
import javax.swing.JScrollBar
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TOOLS_SETTINGS_GROUP = "Tools"
private const val HEXANA_SETTINGS_PAGE = "Hexana"
private const val WASM_TOOLS_LABEL = "Wasm-tools:"
private const val LLVM_OBJDUMP_LABEL = "LLVM objdump:"
private const val USE_BUILT_IN_WASM_TOOLS_CHECKBOX = "Use Built-in wasm tools"
private const val SHOW_OFFSETS_CHECKBOX = "Show offsets"
private const val SEND_ANALYTICS_CHECKBOX = "Send anonymous usage analytics"
private const val BUILD_SETTINGS_GROUP = "Build, Execution, Deployment"
private const val WEBASSEMBLY_SETTINGS_PAGE = "WebAssembly"
private const val WASI_SDK_LABEL = "wasi-sdk:"
private const val LLDB_PATH_LABEL = "LLDB path:"
private const val USE_AS_LLDB_PATH_BUTTON = "Use as LLDB path"
private const val DOWNLOAD_WASI_SDK_ACTION = "Download wasi-sdk…"

internal fun HexanaIde.hexanaSettings(action: HexanaSettingsDsl.() -> Unit) {
    driver.ideFrame {
        val settings = HexanaSettingsDsl(this, HexanaSettingsPage.HEXANA)
        try {
            settings.action()
        } finally {
            settings.close()
        }
    }
}

internal fun HexanaIde.webAssemblySettings(action: HexanaSettingsDsl.() -> Unit) {
    driver.ideFrame {
        val settings = HexanaSettingsDsl(this, HexanaSettingsPage.WEBASSEMBLY)
        try {
            settings.action()
        } finally {
            settings.close()
        }
    }
}

internal class HexanaSettingsDsl internal constructor(
    private val ideFrame: IdeaFrameUI,
    page: HexanaSettingsPage,
) {
    private var dialog: SettingsDialogUiComponent = ideFrame.openSettingsDialog(page)

    fun expectControlsPresent() {
        // A spotlight scroll from the search fallback can land after openSettingsPage's
        // scroll reset and clip the top rows; scroll back to top and retry so it loses.
        waitOneTextAtTopRetrying(WASM_TOOLS_LABEL)
        waitOneTextAtTopRetrying(LLVM_OBJDUMP_LABEL)
        checkBox(USE_BUILT_IN_WASM_TOOLS_CHECKBOX).waitFound(30.seconds)
        checkBox(SHOW_OFFSETS_CHECKBOX).waitFound(30.seconds)
        checkBox(SEND_ANALYTICS_CHECKBOX).waitFound(30.seconds)
    }

    fun openWasiSdkSelectorForCapture() {
        dialog.driver.cast(dialog.component, CaptureComponent::class).setBounds(80, 100, 1560, 900)
        dialog.content {
            waitOneText(WASI_SDK_LABEL, timeout = 30.seconds)
            scrollBars()
                .filter { it.getOrientation() == JScrollBar.VERTICAL }
                .forEach { scrollBar ->
                    val remote = scrollBar.driver.cast(scrollBar.component, CaptureScrollBar::class)
                    remote.setValue(remote.getMaximum())
                }
            waitOneText(LLDB_PATH_LABEL, timeout = 30.seconds)
        }
        val combo = dialog.content().comboBox {
            and(byClass("ComboBox"), byAccessibleName(WASI_SDK_LABEL))
        }.waitFound(30.seconds)
        require(combo.getSelectedItem().contains("wasi-sdk 33.0")) {
            "Expected the installed wasi-sdk 33.0 selector item, got '${combo.getSelectedItem()}'"
        }
        val useButton = dialog.content().button { byText(USE_AS_LLDB_PATH_BUTTON) }.waitFound(30.seconds)
        require(useButton.isEnabled()) { "Expected '$USE_AS_LLDB_PATH_BUTTON' to be enabled" }
        require(DOWNLOAD_WASI_SDK_ACTION in combo.listValues()) {
            "Expected '$DOWNLOAD_WASI_SDK_ACTION' in wasi-sdk selector items: ${combo.listValues()}"
        }

        combo.click()
    }

    internal fun close() {
        runCatching {
            dialog.closeDialog()
        }
    }

    private fun waitOneTextAtTopRetrying(expected: String) {
        repeat(2) {
            runCatching {
                dialog.waitOneTextAtTop(expected, timeout = 10.seconds)
                return
            }
        }
        dialog.waitOneTextAtTop(expected, timeout = 10.seconds)
    }

    private fun checkBox(text: String): JCheckBoxUi =
        dialog.content().checkBox { byText(text) }.waitFound(30.seconds)
}

@Remote("javax.swing.JScrollBar")
private interface CaptureScrollBar {
    fun getMaximum(): Int
    fun setValue(value: Int)
}

@Remote("java.awt.Component")
private interface CaptureComponent {
    fun setBounds(x: Int, y: Int, width: Int, height: Int)
}

internal enum class HexanaSettingsPage {
    HEXANA,
    WEBASSEMBLY,
}

private fun IdeaFrameUI.openSettingsDialog(page: HexanaSettingsPage): SettingsDialogUiComponent {
    openSettingsDialog()
    return settingsDialog()
        .waitFound(30.seconds)
        .apply {
            val path = when (page) {
                HexanaSettingsPage.HEXANA -> arrayOf(TOOLS_SETTINGS_GROUP, HEXANA_SETTINGS_PAGE)
                HexanaSettingsPage.WEBASSEMBLY -> arrayOf(BUILD_SETTINGS_GROUP, WEBASSEMBLY_SETTINGS_PAGE)
            }
            settingsTree.should(
                message = "Settings tree misses ${path.joinToString(" | ")}",
                timeout = 30.seconds,
            ) {
                pathExists(*path)
            }
            openSettingsPage(page)
        }
}

private fun SettingsDialogUiComponent.openSettingsPage(page: HexanaSettingsPage) {
    val path = when (page) {
        HexanaSettingsPage.HEXANA -> arrayOf(TOOLS_SETTINGS_GROUP, HEXANA_SETTINGS_PAGE)
        HexanaSettingsPage.WEBASSEMBLY -> arrayOf(BUILD_SETTINGS_GROUP, WEBASSEMBLY_SETTINGS_PAGE)
    }
    val expectedLabel = when (page) {
        HexanaSettingsPage.HEXANA -> WASM_TOOLS_LABEL
        HexanaSettingsPage.WEBASSEMBLY -> WASI_SDK_LABEL
    }
    repeat(3) {
        runCatching {
            openTreeSettingsSection(*path)
            waitOneTextAtTop(expectedLabel, timeout = 10.seconds)
            return
        }
    }

    searchTextField.text = path.last()
    searchTextField.keyboard {
        key(KeyEvent.VK_ENTER)
    }
    // Search navigation spotlights matching options, which may scroll the page and
    // hide the top labels; retry so a spotlight scroll landing after our reset loses.
    repeat(2) {
        runCatching {
            waitOneTextAtTop(expectedLabel, timeout = 10.seconds)
            return
        }
    }
    waitOneTextAtTop(expectedLabel, timeout = 10.seconds)
}

/** Scrolls the settings page back to the top, then waits for [expected] to be visible. */
private fun SettingsDialogUiComponent.waitOneTextAtTop(expected: String, timeout: Duration) {
    content {
        scrollBars()
            .filter { it.getOrientation() == JScrollBar.VERTICAL }
            .forEach { scrollBar ->
                scrollBar.driver.cast(scrollBar.component, CaptureScrollBar::class).setValue(0)
            }
        waitOneText(expected, timeout = timeout)
    }
}
