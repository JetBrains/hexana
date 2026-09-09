package org.jetbrains.hexana

import com.intellij.openapi.components.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableProvider
import com.intellij.openapi.options.DslConfigurableBase
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.emptyText
import com.intellij.openapi.util.NlsContexts
import com.intellij.util.messages.Topic
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.toNonNullableProperty
import org.jetbrains.annotations.NonNls


@Service
@State(name = "Hexana", storages = [Storage("hexana.xml", roamingType = RoamingType.PER_OS)])
class HexanaSettings : SimplePersistentStateComponent<HexanaSettingsState>(HexanaSettingsState()) {

    companion object {
        val TOPIC: Topic<HexanaSettingsListener> = Topic.create(
            "Hexana settings changes",
            HexanaSettingsListener::class.java,
        )

        @JvmStatic
        fun getInstance(): HexanaSettings = service()
    }

    var wasmToolsPathInUi: String?
        get() = state.wasmToolsPath
        set(value) {
            state.wasmToolsPath = value?.takeIf { it.isNotBlank() }
        }

    var llvmObjdumpPathInUi: String?
        get() = state.llvmObjdumpPath
        set(value) {
            state.llvmObjdumpPath = value?.takeIf { it.isNotBlank() }
        }

    var useBuiltInWasmTools: Boolean?
        get() = true // state.useBuiltInWasmTools
        set(value) {
            state.useBuiltInWasmTools = value?:false
        }

    var showOffsets: Boolean?
        get() = true //state.showOffsets
        set(value) {
            state.showOffsets = value?:false
        }

    var showSemanticHighlighting: Boolean
        get() = state.showSemanticHighlighting
        set(value) {
            state.showSemanticHighlighting = value
            ApplicationManager.getApplication().messageBus
                .syncPublisher(TOPIC)
                .semanticHighlightingChanged(value)
        }

    var sendAnalytics: Boolean
        get() = state.sendAnalytics
        set(value) {
            // Guarded: the settings panel writes bound properties on every
            // Apply, and each publish rebuilds the analytics client.
            if (state.sendAnalytics == value) return
            state.sendAnalytics = value
            ApplicationManager.getApplication().messageBus
                .syncPublisher(TOPIC)
                .sendAnalyticsChanged(value)
        }

    /** Enables SBOM vulnerability matching against a locally downloaded OSV database. */
    var lookupVulnerabilities: Boolean
        get() = state.lookupVulnerabilities
        set(value) {
            state.lookupVulnerabilities = value
        }

    /** Opt-in: also query api.osv.dev online (sends the component list). Requires [lookupVulnerabilities]. */
    var vulnerabilitiesOnline: Boolean
        get() = state.vulnerabilitiesOnline
        set(value) {
            state.vulnerabilitiesOnline = value
        }

    var feedbackPromptDisabled: Boolean
        get() = state.feedbackPromptDisabled
        set(value) {
            state.feedbackPromptDisabled = value
            ApplicationManager.getApplication().messageBus
                .syncPublisher(TOPIC)
                .feedbackPromptVisibilityChanged(!value)
        }

    var feedbackPromptEnabled: Boolean
        get() = !feedbackPromptDisabled
        set(value) {
            feedbackPromptDisabled = !value
        }

    var showWhatsNewOnNewVersion: Boolean
        get() = state.showWhatsNewOnNewVersion
        set(value) {
            state.showWhatsNewOnNewVersion = value
        }

    var lastShownWhatsNewVersion: String?
        get() = state.lastShownWhatsNewVersion
        set(value) {
            state.lastShownWhatsNewVersion = value?.takeIf { it.isNotBlank() }
        }

    val wasmToolsPath: String
        get() = wasmToolsPathInUi ?: "wasm-tools"

    val llvmObjdumpPath: String?
        get() = llvmObjdumpPathInUi

}

interface HexanaSettingsListener {
    fun feedbackPromptVisibilityChanged(enabled: Boolean) {}
    fun semanticHighlightingChanged(enabled: Boolean) {}

    /** Fired when the analytics opt-out is toggled, so the client can be rebuilt. */
    fun sendAnalyticsChanged(enabled: Boolean) {}
}

class HexanaSettingsState : BaseState() {
    var wasmToolsPath: String? by string()
    var llvmObjdumpPath: String? by string()
    var useBuiltInWasmTools: Boolean by property(false)
    var showOffsets: Boolean by property(false)
    var showSemanticHighlighting: Boolean by property(true)
    var sendAnalytics: Boolean by property(true)
    var lookupVulnerabilities: Boolean by property(false)
    var vulnerabilitiesOnline: Boolean by property(false)
    var feedbackPromptDisabled: Boolean by property(false)
    var showWhatsNewOnNewVersion: Boolean by property(true)
    var lastShownWhatsNewVersion: String? by string()
}

class HexanaConfigurableProvider : ConfigurableProvider() {
    override fun createConfigurable(): Configurable {
        return HexanaConfigurable()
    }

    override fun canCreateConfigurable(): Boolean {
        return true
    }
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
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                    .label(HexanaBundle.message("hexana.settings.llvmObjdump"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.llvmObjdumpComment"))
                    .applyToComponent {
                        emptyText.text = HexanaBundle.message("hexana.settings.llvmObjdumpEmptyText")
                    }
                    .bindText(settings::llvmObjdumpPathInUi.toNonNullableProperty(""))
            }
            row {
                checkBox(HexanaBundle.message("hexana.settings.useBuiltInWasmTools"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.builtInWasmToolsComment"))
                    .bindSelected(settings::useBuiltInWasmTools.toNonNullableProperty(false))
            }
            row {
                checkBox(HexanaBundle.message("hexana.settings.showOffsets"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.showOffsetsComment"))
                    .bindSelected(settings::showOffsets.toNonNullableProperty(false))
            }
            row {
                checkBox(HexanaBundle.message("hexana.settings.showSemanticHighlighting"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.showSemanticHighlightingComment"))
                    .bindSelected(settings::showSemanticHighlighting)
            }
            separator()
            row {
                checkBox(HexanaBundle.message("hexana.settings.sendAnalytics"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.sendAnalyticsComment"))
                    .bindSelected(settings::sendAnalytics)
            }
            separator()
            lateinit var lookupCheckbox: com.intellij.ui.dsl.builder.Cell<com.intellij.ui.components.JBCheckBox>
            row {
                lookupCheckbox = checkBox(HexanaBundle.message("hexana.settings.lookupVulnerabilities"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.lookupVulnerabilitiesComment"))
                    .bindSelected(settings::lookupVulnerabilities)
            }
            row {
                checkBox(HexanaBundle.message("hexana.settings.vulnerabilitiesOnline"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.vulnerabilitiesOnlineComment"))
                    .bindSelected(settings::vulnerabilitiesOnline)
                    .enabledIf(lookupCheckbox.selected)
            }
            row {
                checkBox(HexanaBundle.message("hexana.settings.showFeedbackPrompt"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.showFeedbackPromptComment"))
                    .bindSelected(settings::feedbackPromptEnabled)
            }
            separator()
            row {
                checkBox(HexanaBundle.message("hexana.settings.showWhatsNewOnNewVersion"))
                    .align(AlignY.TOP)
                    .align(AlignX.FILL)
                    .comment(HexanaBundle.message("hexana.settings.showWhatsNewOnNewVersionComment"))
                    .bindSelected(settings::showWhatsNewOnNewVersion)
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
