package org.jetbrains.hexana.run

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.DslConfigurableBase
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.emptyText
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNonNullableProperty
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.wasisdk.WasiSdkOffer
import org.jetbrains.hexana.wasisdk.WasiSdkStorage
import javax.swing.DefaultComboBoxModel
import javax.swing.JList

/**
 * Settings page shown under **Build, Execution, Deployment | WebAssembly**.
 *
 * Lets the user configure default executable paths for Wasmtime,
 * WAMR (iwasm), and LLDB. These defaults are inherited by
 * [WasmRunConfiguration] instances when their per-configuration
 * path fields are left blank.
 */
class WasmRuntimeConfigurable : DslConfigurableBase(), SearchableConfigurable {

    /**
     * Sealed type for the wasi-sdk selector combo items.
     * [DownloadAction] is an action item -- selecting it triggers a dialog
     * and the selection is immediately reverted to the previous item.
     */
    private sealed interface WasiSdkItem {
        /** An installed wasi-sdk version. */
        data class Installed(val version: Int, val path: String) : WasiSdkItem
        /** Placeholder shown when no version is installed. */
        object None : WasiSdkItem
        /** Action item -- triggers the version chooser dialog. */
        object DownloadAction : WasiSdkItem
    }

    override fun createPanel(): DialogPanel {
        val settings = WasmRuntimeSettings.getInstance()
        val sdkStorage = WasiSdkStorage.getInstance()

        // Build the combo once; onReset/onApply/onIsModified reference it via closure.
        val wasiSdkCombo = buildWasiSdkCombo(sdkStorage)
        // Assigned when the LLDB path row is built below; the "Use as LLDB path"
        // button writes into this field so the change goes through the normal
        // modified/apply cycle and is visible immediately.
        var lldbPathField: TextFieldWithBrowseButton? = null

        return panel {
            group(HexanaBundle.message("wasm.runtime.settings.group.runtimes")) {
                row(HexanaBundle.message("wasm.runtime.settings.default.engine")) {
                    val items = listOf(WasmRuntimeSettings.ASK_EVERY_TIME) + WasmEngine.entries.map { it.name }
                    val renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
                        label.text = if (value == WasmRuntimeSettings.ASK_EVERY_TIME)
                            HexanaBundle.message("wasm.runtime.settings.default.engine.ask")
                        else
                            WasmEngine.fromString(value).displayName
                    }
                    comboBox(items, renderer)
                        .bindItem(
                            getter = { settings.defaultEngineInUi ?: WasmRuntimeSettings.ASK_EVERY_TIME },
                            setter = { settings.defaultEngineInUi = it },
                        )
                        .comment(HexanaBundle.message("wasm.runtime.settings.default.engine.comment"))
                }
                row(HexanaBundle.message("wasm.runtime.settings.wasmtime.path")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                        .align(AlignX.FILL)
                        .comment(HexanaBundle.message("wasm.runtime.settings.wasmtime.path.comment"))
                        .applyToComponent {
                            emptyText.text = WasmtimeCommandLineState.resolveDefaultWasmtimePath()
                        }
                        .bindText(settings::wasmtimePathInUi.toNonNullableProperty(""))
                }
                row(HexanaBundle.message("wasm.runtime.settings.wamr.path")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                        .align(AlignX.FILL)
                        .comment(HexanaBundle.message("wasm.runtime.settings.wamr.path.comment"))
                        .applyToComponent {
                            emptyText.text = WamrCommandLineState.resolveDefaultWamrPath()
                        }
                        .bindText(settings::wamrPathInUi.toNonNullableProperty(""))
                }
                row(HexanaBundle.message("wasm.runtime.settings.graalvm.path")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir())
                        .align(AlignX.FILL)
                        .comment(HexanaBundle.message("wasm.runtime.settings.graalvm.path.comment"))
                        .bindText(settings::graalVmPathInUi.toNonNullableProperty(""))
                }
                row(HexanaBundle.message("wasm.runtime.settings.node.path")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                        .align(AlignX.FILL)
                        .comment(HexanaBundle.message("wasm.runtime.settings.node.path.comment"))
                        .applyToComponent {
                            emptyText.text = NodeJsCommandLineState.resolveDefaultNodePath()
                        }
                        .bindText(settings::nodePathInUi.toNonNullableProperty(""))
                }
                row(HexanaBundle.message("wasm.runtime.settings.chicory.path")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir())
                        .align(AlignX.FILL)
                        .comment(HexanaBundle.message("wasm.runtime.settings.chicory.path.comment"))
                        .bindText(settings::chicoryPathInUi.toNonNullableProperty(""))
                }
                row(HexanaBundle.message("wasm.runtime.settings.wazero.path")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                        .align(AlignX.FILL)
                        .comment(HexanaBundle.message("wasm.runtime.settings.wazero.path.comment"))
                        .applyToComponent {
                            emptyText.text = WazeroCommandLineState.resolveDefaultWazeroPath()
                        }
                        .bindText(settings::wazeroPathInUi.toNonNullableProperty(""))
                }
            }
            group(HexanaBundle.message("wasm.runtime.settings.group.debugger")) {
                // wasi-sdk row: JDK-style combo with installed versions and a "Download" action item.
                // Selecting an installed version marks it active on apply (not on every change).
                // The "Use as LLDB path" button writes the selected version's lldb into the
                // LLDB path field below -- the same opt-in that the install notification offers.
                row(HexanaBundle.message("wasm.runtime.settings.wasisdk.label")) {
                    cell(wasiSdkCombo).align(AlignX.FILL)
                    button(HexanaBundle.message("wasm.runtime.settings.wasisdk.use.button")) {
                        val item = wasiSdkCombo.selectedItem as? WasiSdkItem.Installed ?: return@button
                        val lldbPath = sdkStorage.lldbPathForVersion(item.version)?.toString() ?: return@button
                        lldbPathField?.text = lldbPath
                    }.applyToComponent {
                        // Enable only when an installed version is selected.
                        isEnabled = wasiSdkCombo.selectedItem is WasiSdkItem.Installed
                        wasiSdkCombo.addItemListener { isEnabled = wasiSdkCombo.selectedItem is WasiSdkItem.Installed }
                    }
                }.comment(HexanaBundle.message("wasm.runtime.settings.wasisdk.combo.comment"))
                onApply {
                    val item = wasiSdkCombo.selectedItem
                    sdkStorage.setActiveVersion(if (item is WasiSdkItem.Installed) item.version else null)
                }
                onReset {
                    resetWasiSdkCombo(wasiSdkCombo, sdkStorage)
                }
                onIsModified {
                    val item = wasiSdkCombo.selectedItem
                    val selectedVersion = if (item is WasiSdkItem.Installed) item.version else null
                    selectedVersion != sdkStorage.activeVersion()
                }

                row(HexanaBundle.message("wasm.runtime.settings.lldb.path")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                        .align(AlignX.FILL)
                        .comment(HexanaBundle.message("wasm.runtime.settings.lldb.path.comment"))
                        .applyToComponent {
                            emptyText.text = WasmDebugRunner.resolveDefaultLldbPath()
                            lldbPathField = this
                        }
                        .bindText(settings::lldbPathInUi.toNonNullableProperty(""))
                }
                row(HexanaBundle.message("wasm.runtime.settings.chrome.path")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                        .align(AlignX.FILL)
                        .comment(HexanaBundle.message("wasm.runtime.settings.chrome.path.comment"))
                        .applyToComponent {
                            emptyText.text = CdpDebugRunner.resolveDefaultChromePath()
                        }
                        .bindText(settings::chromePathInUi.toNonNullableProperty(""))
                }
            }
        }
    }

    /**
     * Builds the wasi-sdk combo with [JDK-style rendering][ColoredListCellRenderer]:
     * installed versions show a bold name and a gray path; a separator-ish gap
     * precedes the "Download wasi-sdk..." action item. Selecting the action item
     * restores the previous selection and opens the version chooser dialog.
     */
    private fun buildWasiSdkCombo(sdkStorage: WasiSdkStorage): ComboBox<WasiSdkItem> {
        val model = buildComboModel(sdkStorage)
        val combo = ComboBox(model)
        combo.renderer = WasiSdkItemRenderer()

        // Tracks the last real (non-action) selection so picking the download
        // action and cancelling the dialog restores what was selected before.
        var lastSelection: WasiSdkItem? = null
        combo.addItemListener { event ->
            if (event.stateChange != java.awt.event.ItemEvent.SELECTED) return@addItemListener
            val item = event.item as? WasiSdkItem ?: return@addItemListener
            if (item is WasiSdkItem.DownloadAction) {
                val currentModel = combo.model as DefaultComboBoxModel<WasiSdkItem>
                val previous = lastSelection ?: findPreviousInstalledOrNone(currentModel)
                // Defer the revert so the combo finishes its own selection handling first.
                invokeLater {
                    combo.selectedItem = previous
                    WasiSdkOffer.showVersionChooserDialog(null) { installedVersion ->
                        // Refresh the model after a successful install and select the new version.
                        val newModel = buildComboModel(sdkStorage)
                        combo.model = newModel
                        val newItem = findInstalledItem(newModel, installedVersion)
                        combo.selectedItem = newItem ?: findPreviousInstalledOrNone(newModel)
                    }
                }
            } else {
                lastSelection = item
            }
        }

        // Set initial selection to the active version (or None if nothing active/installed).
        val activeVersion = sdkStorage.activeVersion()
        combo.selectedItem = if (activeVersion != null) {
            findInstalledItem(model, activeVersion) ?: WasiSdkItem.None
        } else {
            WasiSdkItem.None
        }

        return combo
    }

    /** Rebuilds the combo model from the current disk state. */
    private fun buildComboModel(sdkStorage: WasiSdkStorage): DefaultComboBoxModel<WasiSdkItem> {
        val installed = sdkStorage.installedVersions()
        val model = DefaultComboBoxModel<WasiSdkItem>()
        if (installed.isEmpty()) {
            model.addElement(WasiSdkItem.None)
        } else {
            for (version in installed) {
                // lldbPathForVersion returns <root>/wasi-sdk-N.0/bin/lldb; show the install dir.
                val path = sdkStorage.lldbPathForVersion(version)?.parent?.parent?.toString() ?: ""
                model.addElement(WasiSdkItem.Installed(version, path))
            }
        }
        model.addElement(WasiSdkItem.DownloadAction)
        return model
    }

    /** Resets the combo to reflect the current active version on disk (called by onReset). */
    private fun resetWasiSdkCombo(combo: ComboBox<WasiSdkItem>, sdkStorage: WasiSdkStorage) {
        val newModel = buildComboModel(sdkStorage)
        combo.model = newModel
        val activeVersion = sdkStorage.activeVersion()
        combo.selectedItem = if (activeVersion != null) {
            findInstalledItem(newModel, activeVersion) ?: WasiSdkItem.None
        } else {
            WasiSdkItem.None
        }
    }

    private fun findInstalledItem(model: DefaultComboBoxModel<WasiSdkItem>, version: Int): WasiSdkItem.Installed? {
        for (i in 0 until model.size) {
            val item = model.getElementAt(i)
            if (item is WasiSdkItem.Installed && item.version == version) return item
        }
        return null
    }

    /** Returns the first [WasiSdkItem.Installed] in the model, or [WasiSdkItem.None] if none. */
    private fun findPreviousInstalledOrNone(model: DefaultComboBoxModel<WasiSdkItem>): WasiSdkItem {
        for (i in 0 until model.size) {
            val item = model.getElementAt(i)
            if (item is WasiSdkItem.Installed) return item
        }
        return WasiSdkItem.None
    }

    /** Renders wasi-sdk combo items in JDK-combo style. */
    private inner class WasiSdkItemRenderer : ColoredListCellRenderer<WasiSdkItem>() {
        override fun customizeCellRenderer(
            list: JList<out WasiSdkItem>,
            value: WasiSdkItem?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            when (val item = value) {
                is WasiSdkItem.Installed -> {
                    append("wasi-sdk ${item.version}.0", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (item.path.isNotEmpty()) {
                        append("  ${item.path}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
                WasiSdkItem.None -> {
                    append(
                        HexanaBundle.message("wasm.runtime.settings.wasisdk.status.none"),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
                WasiSdkItem.DownloadAction -> {
                    icon = AllIcons.Actions.Download
                    append(HexanaBundle.message("wasm.runtime.settings.wasisdk.download.action"))
                }
                null -> {}
            }
        }
    }

    override fun getId(): @NonNls String = "wasm.runtime.settings"

    override fun getDisplayName(): @NlsContexts.ConfigurableName String =
        HexanaBundle.message("wasm.runtime.settings.displayName")
}
