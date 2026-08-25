package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.hexana.shared.scripting.ScriptApiReference
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * Dialog shown when the user clicks the "+" button to add a custom script tab.
 *
 * Besides picking an existing `.js` file it offers a runnable starting point —
 * the starter template or one of the bundled examples — because the hardest part
 * of writing a script tab used to be the empty file. The API listing is rendered
 * from [ScriptApiReference], the same source as the bundled `.d.ts`, so the
 * dialog cannot describe an API that does not exist.
 */
internal class AddScriptTabDialog(
    private val project: Project,
    private val existingScriptPaths: Set<String> = emptySet(),
) : DialogWrapper(project, false) {

    var selectedFile: VirtualFile? = null
        private set

    private var pathField: JTextField? = null

    init {
        title = "Add Custom Script Tab"
        setOKButtonText("Add")
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                text(
                    "Create a custom analysis tab from a JavaScript file.<br>" +
                        "The script runs against the open binary and renders text, or a searchable<br>" +
                        "table when it starts with a <code>table(...)</code> header row."
                )
            }
            row("Script file:") {
                val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("js")
                    .withTitle("Select Script File")
                textFieldWithBrowseButton(descriptor, project).align(AlignX.FILL).applyToComponent {
                    pathField = textField
                }.onChanged { field ->
                    selectFile(field.text)
                }
            }
            row {
                button("New Script from Template") {
                    ScriptTemplateWriter.createFromTemplate(project)?.let(::useCreatedFile)
                }.comment(
                    "Writes a runnable script into <code>.hexana/scripts</code>, opens it in the " +
                        "editor, and selects it here. The type definitions are placed next to it, " +
                        "so <code>binary.</code> completes as you edit."
                )
            }
            collapsibleGroup("Start from an Example") {
                for (example in ScriptApiReference.examples) {
                    row {
                        link(example.title) {
                            ScriptTemplateWriter.createFromExample(project, example.fileName)
                                ?.let(::useCreatedFile)
                        }
                        comment(example.summary)
                    }
                }
            }
            separator()
            group("Available APIs") {
                row {
                    text(ScriptApiReference.summaryLines().joinToString("<br>") { line ->
                        val separator = line.indexOf(" — ")
                        if (separator < 0) line else {
                            "<b>${line.substring(0, separator)}</b>${line.substring(separator)}"
                        }
                    })
                }
                row {
                    link("Open the scripting API reference") {
                        BinaryProviderApiSummary.showDtsInEditor(project)
                    }
                }
                row {
                    link("Open the format-specific types behind binary.raw") {
                        BinaryProviderApiSummary.showRawDtsInEditor(project)
                    }
                }
            }
        }
    }

    private fun useCreatedFile(file: VirtualFile) {
        selectedFile = file
        pathField?.text = file.path
    }

    private fun selectFile(path: String) {
        selectedFile = if (path.isNotBlank()) {
            LocalFileSystem.getInstance().findFileByPath(path)
        } else {
            null
        }
    }

    override fun doOKAction() {
        val file = selectedFile
        if (file == null) {
            setErrorText("Select a script file, or create one from the template.")
            return
        }
        if (file.path in existingScriptPaths) {
            setErrorText("A tab for this script file is already open.")
            return
        }
        super.doOKAction()
    }
}
