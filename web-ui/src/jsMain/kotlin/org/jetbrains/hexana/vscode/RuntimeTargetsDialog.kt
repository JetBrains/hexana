package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.shared.features.RUNTIME_TARGETS_PATH
import org.jetbrains.hexana.shared.features.RuntimeTarget
import org.jetbrains.hexana.shared.features.RuntimeTargets
import org.jetbrains.hexana.shared.features.WasmFeatureCatalog

/**
 * Settings dialog for the runtimes this project is expected to run on — the VS Code
 * counterpart of the IntelliJ `Runtime Compatibility` settings page.
 *
 * A dialog inside the editor webview rather than a `contributes.configuration` entry,
 * because the selection has to land in `.hexana/runtimes.json` in the project so the
 * IntelliJ plugin reads the same declaration; VS Code settings would be per-user and
 * invisible to the other editor.
 *
 * Runtime names and version suggestions both come from `features.json`, so the values
 * written to disk are upstream's own keys.
 *
 * @param onConfirm receives the edited declaration; the caller serializes and hands it to
 *   the host, which owns the file write
 */
@Composable
fun RuntimeTargetsDialog(
    catalog: WasmFeatureCatalog,
    current: RuntimeTargets,
    onConfirm: (RuntimeTargets) -> Unit,
    onCancel: () -> Unit,
) {
    // Keyed by runtime id: present means selected, value is the version ("" = any).
    val selected = remember(current) {
        mutableStateMapOf<String, String>().apply {
            current.targets.forEach { put(it.runtime, it.version.orEmpty()) }
        }
    }

    Div({
        classes("hexana-dialog-overlay")
        onClick { onCancel() }
    }) {
        Div({
            classes("hexana-dialog", "hexana-runtime-targets-dialog")
            onClick { it.stopPropagation() }
        }) {
            Div({ classes("hexana-dialog-title") }) { Text("Target Runtimes") }

            Div({ classes("hexana-dialog-body") }) {
                P {
                    Text("Choose the runtimes this project is expected to run on. Hexana warns ")
                    Text("when a Wasm module uses a proposal one of them cannot run, and shows the ")
                    Text("full matrix in the Compatibility tab.")
                }
                P({ classes("hexana-dialog-hint") }) {
                    Text("Saved to $RUNTIME_TARGETS_PATH in the workspace, so the IntelliJ plugin ")
                    Text("reads the same selection. Leave a version empty to target any version.")
                }

                Div({ classes("hexana-runtime-targets-list") }) {
                    for ((category, runtimeIds) in catalog.runtimesByCategory()) {
                        Div({ classes("hexana-runtime-targets-category") }) { Text(category) }
                        for (runtimeId in runtimeIds) {
                            RuntimeTargetRow(
                                runtimeId = runtimeId,
                                knownVersions = catalog.runtime(runtimeId)?.knownVersions ?: emptyList(),
                                version = selected[runtimeId],
                                onToggle = { checked ->
                                    if (checked) selected[runtimeId] = "" else selected.remove(runtimeId)
                                },
                                onVersionChange = { version -> selected[runtimeId] = version },
                            )
                        }
                    }
                }
            }

            Div({ classes("hexana-dialog-buttons") }) {
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-cancel")
                    onClick { onCancel() }
                }) { Text("Cancel") }
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-ok")
                    onClick {
                        // Catalog order, so the file's target order is stable across saves.
                        val targets = catalog.runtimesByCategory()
                            .flatMap { (_, runtimeIds) -> runtimeIds }
                            .filter { it in selected }
                            .map { RuntimeTarget(it, selected[it]?.takeIf { v -> v.isNotBlank() }) }
                        onConfirm(RuntimeTargets(targets))
                    }
                }) { Text("Save") }
            }
        }
    }
}

/**
 * One runtime row: a checkbox and a version picker.
 *
 * A `Select` rather than a free-text field: it matches the dialog pattern already used in
 * `FunctionArgsDialog`, and a version the catalog does not list can still be set by editing
 * `.hexana/runtimes.json` directly, which is a supported way to use the file.
 */
@Composable
private fun RuntimeTargetRow(
    runtimeId: String,
    knownVersions: List<String>,
    version: String?,
    onToggle: (Boolean) -> Unit,
    onVersionChange: (String) -> Unit,
) {
    val isSelected = version != null

    Div({ classes("hexana-runtime-targets-row") }) {
        Label(attrs = { classes("hexana-runtime-targets-label") }) {
            CheckboxInput(checked = isSelected) {
                onChange { event -> onToggle(event.value) }
            }
            Text(runtimeId)
        }
        Select({
            classes("hexana-dialog-input", "hexana-runtime-targets-version")
            if (!isSelected) attr("disabled", "")
            onChange { event -> onVersionChange(event.value.orEmpty()) }
        }) {
            Option("", { if (version.isNullOrEmpty()) attr("selected", "") }) { Text(ANY_VERSION_LABEL) }
            for (knownVersion in knownVersions) {
                Option(knownVersion, { if (version == knownVersion) attr("selected", "") }) {
                    Text(knownVersion)
                }
            }
            // A version set by hand in the file but absent from the catalog stays selectable
            // rather than silently reverting to "any" the next time the dialog is opened.
            if (!version.isNullOrEmpty() && version !in knownVersions) {
                Option(version, { attr("selected", "") }) { Text(version) }
            }
        }
    }
}

/** Shown for "no version gate"; serialized as an absent `version`. */
private const val ANY_VERSION_LABEL = "Any version"
