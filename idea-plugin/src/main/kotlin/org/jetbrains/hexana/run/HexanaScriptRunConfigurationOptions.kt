package org.jetbrains.hexana.run

import com.intellij.execution.configurations.RunConfigurationOptions

class HexanaScriptRunConfigurationOptions : RunConfigurationOptions() {
    private val scriptPath = string("").provideDelegate(this, "scriptPath")
    private val inputPaths = list<String>().provideDelegate(this, "inputPaths")
    private val flagValues = list<String>().provideDelegate(this, "flagValues")
    private val freeFormFlags = string("").provideDelegate(this, "freeFormFlags")
    private val baselinesDir = string("").provideDelegate(this, "baselinesDir")

    fun getScriptPath(): String = scriptPath.getValue(this) ?: ""
    fun setScriptPath(value: String) { scriptPath.setValue(this, value) }

    fun getInputPaths(): List<String> = inputPaths.getValue(this).toList()
    fun setInputPaths(value: List<String>) {
        inputPaths.getValue(this).run { clear(); addAll(value) }
    }

    /**
     * Flag values stored as "name=value" strings. One entry per declared flag
     * that has a non-null value. Bool flags without a value use "name=true".
     * When the form is in degraded mode, this list is empty and freeFormFlags is used.
     */
    fun getFlagValues(): List<String> = flagValues.getValue(this).toList()
    fun setFlagValues(value: List<String>) {
        flagValues.getValue(this).run { clear(); addAll(value) }
    }

    fun getFreeFormFlags(): String = freeFormFlags.getValue(this) ?: ""
    fun setFreeFormFlags(value: String) { freeFormFlags.setValue(this, value) }

    fun getBaselinesDir(): String = baselinesDir.getValue(this) ?: ""
    fun setBaselinesDir(value: String) { baselinesDir.setValue(this, value) }
}
