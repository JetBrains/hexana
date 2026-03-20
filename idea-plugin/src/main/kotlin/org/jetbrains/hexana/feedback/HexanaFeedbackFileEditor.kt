package org.jetbrains.hexana.feedback

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import org.jetbrains.annotations.Nls
import org.jetbrains.hexana.ui.createComposePanel
import java.beans.PropertyChangeListener
import javax.swing.JComponent

internal class HexanaFeedbackFileEditor(
    private val project: Project,
    private val feedbackFile: HexanaFeedbackVirtualFile,
) : FileEditor, UserDataHolderBase() {
    private val technicalContextPreview = createTechnicalContextPreview(project)

    private val panel: JComponent = createComposePanel {
        HexanaFeedbackScreen(
            initialState = feedbackFile.getUserData(HexanaFeedbackVirtualFile.STATE_KEY) ?: SurveyUiState(),
            technicalContextPreview = technicalContextPreview,
            onStateChange = { feedbackFile.putUserData(HexanaFeedbackVirtualFile.STATE_KEY, it) },
            onAction = { action, state, dispatch ->
                when (action) {
                    SurveyAction.Later -> HexanaFeedbackOpener.close(project, feedbackFile)
                    SurveyAction.Submit -> submitHexanaFeedback(
                        state = state,
                        technicalContextPreview = technicalContextPreview,
                        onDone = {
                            ApplicationManager.getApplication().invokeLater {
                                dispatch(SurveyAction.SubmitSucceeded)
                            }
                        },
                        onError = {
                            ApplicationManager.getApplication().invokeLater {
                                dispatch(SurveyAction.SubmitFailed)
                            }
                        },
                    )
                    SurveyAction.OpenIssue -> BrowserUtil.browse(HexanaIssueLinks.bugReportUrl(technicalContextPreview))
                    SurveyAction.CloseTab -> HexanaFeedbackOpener.close(project, feedbackFile)
                    else -> Unit
                }
            },
        )
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent? = panel

    override fun getName(): @Nls(capitalization = Nls.Capitalization.Title) String = "Feedback"

    override fun setState(state: FileEditorState) = Unit

    override fun getState(level: FileEditorStateLevel): FileEditorState = FeedbackFileEditorState

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile(): HexanaFeedbackVirtualFile = feedbackFile

    override fun dispose() = Unit
}

private object FeedbackFileEditorState : FileEditorState {
    override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean = false
}
