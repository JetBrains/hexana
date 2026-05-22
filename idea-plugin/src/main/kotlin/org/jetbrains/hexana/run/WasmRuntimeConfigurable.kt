package org.jetbrains.hexana.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.DslConfigurableBase
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.emptyText
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNonNullableProperty
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.HexanaBundle

/**
 * Settings page shown under **Build, Execution, Deployment | WebAssembly**.
 *
 * Lets the user configure default executable paths for Wasmtime,
 * WAMR (iwasm), and LLDB. These defaults are inherited by
 * [WasmRunConfiguration] instances when their per-configuration
 * path fields are left blank.
 */
class WasmRuntimeConfigurable : DslConfigurableBase(), SearchableConfigurable {

    override fun createPanel(): DialogPanel {
        val settings = WasmRuntimeSettings.getInstance()
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
            }
            group(HexanaBundle.message("wasm.runtime.settings.group.debugger")) {
                row(HexanaBundle.message("wasm.runtime.settings.lldb.path")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                        .align(AlignX.FILL)
                        .comment(HexanaBundle.message("wasm.runtime.settings.lldb.path.comment"))
                        .applyToComponent {
                            emptyText.text = WasmDebugRunner.resolveDefaultLldbPath()
                        }
                        .bindText(settings::lldbPathInUi.toNonNullableProperty(""))
                }
            }
        }
    }

    override fun getId(): @NonNls String = "wasm.runtime.settings"

    override fun getDisplayName(): @NlsContexts.ConfigurableName String =
        HexanaBundle.message("wasm.runtime.settings.displayName")
}
