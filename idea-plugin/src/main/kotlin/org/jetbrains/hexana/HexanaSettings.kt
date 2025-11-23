package org.jetbrains.hexana

import com.intellij.openapi.components.*
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.DslConfigurableBase
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.emptyText
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNonNullableProperty
import org.jetbrains.annotations.NonNls


@Service
@State(name = "Hexana", storages = [Storage("hexana.xml", roamingType = RoamingType.PER_OS)])
class HexanaSettings : SimplePersistentStateComponent<HexanaSettingsState>(HexanaSettingsState()) {

    companion object {
        @JvmStatic
        fun getInstance(): HexanaSettings = service()
    }

    var wasmToolsPathInUi: String?
        get() = state.wasmToolsPath
        set(value) {
            state.wasmToolsPath = value?.takeIf { it.isNotBlank() }
        }
    var useBuiltInWasmTools: Boolean?
        get() = state.useBuiltInWasmTools
        set(value) {
            state.useBuiltInWasmTools = value?:false
        }

    val wasmToolsPath: String
        get() = wasmToolsPathInUi ?: "wasm-tools"

}

class HexanaSettingsState : BaseState() {
    var wasmToolsPath: String? by string()
    var useBuiltInWasmTools: Boolean by property(false)
}

class HexanaConfigurable : DslConfigurableBase(), SearchableConfigurable {
    override fun createPanel(): DialogPanel {
        val settings = HexanaSettings.getInstance()
        return panel {
            row {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                    .label(HexanaBundle.message("hexana.settings.wasmTools"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.wasmToolsComment"))
                    .applyToComponent {
                        emptyText.text = HexanaBundle.message("hexana.settings.wasmToolsEmptyText")
                    }
                    .bindText(settings::wasmToolsPathInUi.toNonNullableProperty(""))
            }
            row {
                checkBox(HexanaBundle.message("hexana.settings.useBuiltInWasmTools"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.builtInWasmToolsComment"))
                    .bindSelected(settings::useBuiltInWasmTools.toNonNullableProperty(false))
            }
        }
    }

    override fun getId(): @NonNls String {
        return "hexana.settings"
    }

    override fun getDisplayName(): @NlsContexts.ConfigurableName String {
        return HexanaBundle.message("hexana.settings.displayName")
    }
}
