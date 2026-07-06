package org.jetbrains.hexana.ui

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Project-level persistent storage for user-added script tabs.
 *
 * Each entry maps a binary file path (the file opened in the hex editor)
 * to the list of script (.js) file paths the user attached as tabs.
 */
@Service(Service.Level.PROJECT)
@State(name = "HexanaScriptTabs", storages = [Storage("hexanaScriptTabs.xml")])
class ScriptTabSettings : SimplePersistentStateComponent<ScriptTabSettingsState>(ScriptTabSettingsState()) {

    companion object {
        fun getInstance(project: Project): ScriptTabSettings = project.service()
    }

    fun getScriptPaths(binaryFilePath: String): List<String> {
        return state.entries[binaryFilePath]?.paths?.toList() ?: emptyList()
    }

    fun addScriptPath(binaryFilePath: String, scriptPath: String) {
        val current = state.entries[binaryFilePath]?.paths?.toList() ?: emptyList()
        if (scriptPath !in current) {
            val entry = ScriptTabEntry().also { it.paths = (current + scriptPath).toMutableList() }
            state.entries = state.entries.toMutableMap().also { it[binaryFilePath] = entry }
        }
    }

    fun removeScriptPath(binaryFilePath: String, scriptPath: String) {
        val current = state.entries[binaryFilePath]?.paths?.toList() ?: return
        val updated = current - scriptPath
        if (updated.isEmpty()) {
            state.entries = state.entries.toMutableMap().also { it.remove(binaryFilePath) }
        } else {
            val entry = ScriptTabEntry().also { it.paths = updated.toMutableList() }
            state.entries = state.entries.toMutableMap().also { it[binaryFilePath] = entry }
        }
    }
}

class ScriptTabSettingsState : BaseState() {
    @get:XCollection
    var entries by map<String, ScriptTabEntry>()
}

class ScriptTabEntry : BaseState() {
    @get:XCollection
    var paths by list<String>()
}
