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
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.annotations.NonNls
import java.nio.file.Path


@Service
@State(name = "Hexana", storages = [Storage("hexana.xml", roamingType = RoamingType.PER_OS)])
class HexanaSettings : SimplePersistentStateComponent<HexanaSettingsState>(HexanaSettingsState()) {

    companion object {
        @JvmStatic
        fun getInstance(): HexanaSettings = service()
    }

    var wabtDir: String
        get() = state.wabtDir ?: ""
        set(value) {
            state.wabtDir = value.takeIf { it.isNotBlank() }
        }

    val wasm2watExecPath: String
        get() {
            return wabtDir.takeIf { it.isNotBlank() }?.let {
                Path.of(it, "wasm2wat").toString()
            } ?: "wasm2wat"
        }

}

class HexanaSettingsState : BaseState() {
    var wabtDir: String? by string()
}

class HexanaConfigurable : DslConfigurableBase(), SearchableConfigurable {
    override fun createPanel(): DialogPanel {
        val settings = HexanaSettings.getInstance()
        return panel {
            row {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir())
                    .label(HexanaBundle.message("hexana.settings.wabtDirLabel"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.wabtDirComment"))
                    .applyToComponent {
                        emptyText.text = HexanaBundle.message("hexana.settings.wabtDirEmptyText")
                    }
                    .bindText(settings::wabtDir)
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
