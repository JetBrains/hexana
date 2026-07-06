package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Dialog shown when the user clicks the "+" button to add a custom script tab.
 *
 * Explains the concept, documents the available JS APIs (`print`, `binary`),
 * and lets the user pick a `.js` file.
 */
internal class AddScriptTabDialog(
    private val project: Project,
    private val existingScriptPaths: Set<String> = emptySet(),
) : DialogWrapper(project, false) {

    var selectedFile: VirtualFile? = null
        private set

    init {
        title = "Add Custom Script Tab"
        setOKButtonText("Add")
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                text(
                    "Create a custom analysis tab by selecting a JavaScript file.<br>" +
                        "The script runs with access to the opened binary and can produce<br>" +
                        "text or tabular (CSV/TSV) output displayed in the tab."
                )
            }
            row("Script file:") {
                val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("js")
                    .withTitle("Select Script File")
                textFieldWithBrowseButton(descriptor, project).align(AlignX.FILL).onChanged { field ->
                    val path = field.text
                    selectedFile = if (path.isNotBlank()) {
                        com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
                    } else {
                        null
                    }
                }
            }
            separator()
            group("Available APIs") {
                row {
                    text(
                        "<b>print(text)</b> — appends a line to the tab output.<br>" +
                            "<b>binary</b> — the current file's BinaryProvider object with properties such as:<br>" +
                            "&nbsp;&nbsp;&nbsp;&nbsp;<b>binary.strings</b> — list of extracted strings (each has <code>.value</code>)<br>" +
                            "&nbsp;&nbsp;&nbsp;&nbsp;<b>binary.bytes</b> — raw byte buffer of the file<br>" +
                            "&nbsp;&nbsp;&nbsp;&nbsp;<b>binary.sizeAnalysis</b> — hierarchical size breakdown (if available)"
                    )
                }
            }
        }
    }

    override fun doOKAction() {
        if (selectedFile == null) return
        if (selectedFile!!.path in existingScriptPaths) {
            setErrorText("A tab for this script file is already open.")
            return
        }
        super.doOKAction()
    }
}
