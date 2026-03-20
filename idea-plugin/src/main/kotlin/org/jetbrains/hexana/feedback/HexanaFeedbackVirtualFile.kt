package org.jetbrains.hexana.feedback

import com.intellij.openapi.util.Key
import com.intellij.testFramework.LightVirtualFile

internal class HexanaFeedbackVirtualFile : LightVirtualFile(
    "Hexana Feedback.hexana-feedback",
    HexanaFeedbackFileType.INSTANCE,
    "",
) {
    companion object {
        val STATE_KEY: Key<SurveyUiState> = Key.create("hexana.feedback.state")
    }

    override fun isWritable(): Boolean = false
}
