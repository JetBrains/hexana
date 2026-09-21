package org.jetbrains.hexana.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import java.io.File

final class HexanaScriptRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<HexanaScriptRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): HexanaScriptRunConfigurationOptions =
        super.getOptions() as HexanaScriptRunConfigurationOptions

    var scriptPath: String
        get() = options.getScriptPath()
        set(value) { options.setScriptPath(value) }

    var inputPaths: List<String>
        get() = options.getInputPaths()
        set(value) { options.setInputPaths(value) }

    /** "name=value" encoded flag values; empty when using free-form mode. */
    var flagValues: List<String>
        get() = options.getFlagValues()
        set(value) { options.setFlagValues(value) }

    var freeFormFlags: String
        get() = options.getFreeFormFlags()
        set(value) { options.setFreeFormFlags(value) }

    var baselinesDir: String
        get() = options.getBaselinesDir()
        set(value) { options.setBaselinesDir(value) }

    override fun checkConfiguration() {
        val sp = scriptPath
        if (sp.isBlank()) throw RuntimeConfigurationError("Script path is not specified")
        if (!File(sp).exists()) throw RuntimeConfigurationError("Script file not found: $sp")
    }

    override fun getConfigurationEditor(): SettingsEditor<HexanaScriptRunConfiguration> =
        HexanaScriptRunSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        HexanaScriptCommandState(this, environment)
}
