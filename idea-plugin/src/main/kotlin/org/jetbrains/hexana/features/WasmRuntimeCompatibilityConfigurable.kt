package org.jetbrains.hexana.features

import com.intellij.openapi.options.DslConfigurableBase
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.shared.features.RUNTIME_TARGETS_PATH
import org.jetbrains.hexana.shared.features.RuntimeTarget
import org.jetbrains.hexana.shared.features.RuntimeTargets
import javax.swing.DefaultComboBoxModel

/**
 * Settings page for the runtimes this project is expected to run on, shown under
 * **Build, Execution, Deployment | WebAssembly | Runtime Compatibility**.
 *
 * A *project* configurable, not an application one, because the selection is written to
 * `.hexana/runtimes.json` in the project so the VS Code extension reads the same
 * declaration. Nothing here goes into `.idea/`.
 *
 * The runtime rows and the version suggestions both come from WebAssembly's
 * `features.json`, so the names written to disk are upstream's own keys and no second
 * naming vocabulary has to be maintained.
 */
class WasmRuntimeCompatibilityConfigurable(private val project: Project) :
    DslConfigurableBase(), SearchableConfigurable {

    /** The editable state of one runtime row. */
    private class RuntimeRow(
        val runtimeId: String,
        val checkBox: JBCheckBox,
        val versionCombo: ComboBox<String>,
    ) {
        /** `null` when the row is unchecked. */
        fun toTarget(): RuntimeTarget? {
            if (!checkBox.isSelected) return null
            val version = (versionCombo.editor.item as? String)?.trim()
            return RuntimeTarget(runtimeId, version?.takeIf { it.isNotEmpty() && it != ANY_VERSION })
        }

        fun applyTarget(target: RuntimeTarget?) {
            checkBox.isSelected = target != null
            versionCombo.isEnabled = target != null
            versionCombo.selectedItem = target?.version ?: ANY_VERSION
            versionCombo.editor.item = target?.version ?: ANY_VERSION
        }
    }

    private val rows = mutableListOf<RuntimeRow>()

    override fun createPanel(): DialogPanel {
        rows.clear()
        val catalog = WasmFeatureCatalogService.getInstance().catalog

        return panel {
            row {
                comment(HexanaBundle.message("wasm.runtime.compatibility.description", RUNTIME_TARGETS_PATH))
            }

            for ((category, runtimeIds) in catalog.runtimesByCategory()) {
                collapsibleGroup(category, indent = false) {
                    for (runtimeId in runtimeIds) {
                        val knownVersions = catalog.runtime(runtimeId)?.knownVersions ?: emptyList()
                        row {
                            val checkBox = JBCheckBox(runtimeId)
                            val versionCombo = buildVersionCombo(knownVersions)
                            checkBox.addActionListener { versionCombo.isEnabled = checkBox.isSelected }
                            cell(checkBox)
                            cell(versionCombo).align(AlignX.LEFT)
                            rows += RuntimeRow(runtimeId, checkBox, versionCombo)
                        }
                    }
                }.expanded = category in DEFAULT_EXPANDED_CATEGORIES
            }

            row {
                button(HexanaBundle.message("wasm.runtime.compatibility.refresh")) {
                    WasmFeatureCatalogService.getInstance().refreshNow()
                }.comment(HexanaBundle.message("wasm.runtime.compatibility.refresh.comment"))
            }

            onReset { resetRows() }
            onApply { RuntimeTargetsService.getInstance(project).save(collectTargets()) }
            onIsModified { collectTargets().targets != currentTargets() }
        }
    }

    /**
     * Editable so a version upstream has not recorded yet (a nightly, an internal build) can
     * still be typed in; the listed versions are the ones `features.json` mentions for this
     * runtime, newest first.
     */
    private fun buildVersionCombo(knownVersions: List<String>): ComboBox<String> {
        val model = DefaultComboBoxModel(arrayOf(ANY_VERSION) + knownVersions.toTypedArray())
        return ComboBox(model).apply {
            isEditable = true
            isEnabled = false
            selectedItem = ANY_VERSION
        }
    }

    private fun currentTargets(): List<RuntimeTarget> =
        RuntimeTargetsService.getInstance(project).targets.targets

    private fun collectTargets(): RuntimeTargets = RuntimeTargets(rows.mapNotNull { it.toTarget() })

    private fun resetRows() {
        val declared = currentTargets().associateBy { it.runtime }
        for (row in rows) {
            row.applyTarget(declared[row.runtimeId])
        }
    }

    override fun getId(): @NonNls String = ID

    override fun getDisplayName(): @NlsContexts.ConfigurableName String =
        HexanaBundle.message("wasm.runtime.compatibility.displayName")

    companion object {
        const val ID: @NonNls String = "wasm.runtime.compatibility"

        /** Combo entry meaning "no version gate"; serialized as an absent `version`. */
        private const val ANY_VERSION = "Any version"

        /** The two categories most projects target; the rest start collapsed. */
        private val DEFAULT_EXPANDED_CATEGORIES = setOf("Web Browsers", "Standalone Runtimes")
    }
}
