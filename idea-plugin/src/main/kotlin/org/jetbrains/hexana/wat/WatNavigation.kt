package org.jetbrains.hexana.wat

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

class WatNavigation {
    companion object {
        val WAT_EDITOR = Key.create<Editor>("hexana_wat_editor")

        /**
         * Handles navigation in the preview wat files.
         * Returns true if the navigation was handled, false if
         * the default navigation logic should be used.
         *
         * The preview wat file created on the fly requires special handling because
         * EditorComposite (the thing which contains all editors of the file) has only
         * one VirtualFile (the original wasm file).
         *
         * When the user navigates in the wat preview file, Idea fails to find
         * the EditorComposite for it (wat file != wasm file) and opens
         * the preview wat file in a new composite.
         *
         * To work that around, we find put the editor where the navigation should happen
         * in user data and move to the desired offset manually.
         */
        fun navigateInWatEditor(watFile: VirtualFile, offset: Int): Boolean {
            val watEditor = watFile.getUserData(WAT_EDITOR) ?: return false
            watEditor.caretModel.moveToOffset(offset)
            watEditor.scrollingModel.scrollTo(watEditor.offsetToLogicalPosition(offset), ScrollType.MAKE_VISIBLE)
            return true
        }
    }
}

