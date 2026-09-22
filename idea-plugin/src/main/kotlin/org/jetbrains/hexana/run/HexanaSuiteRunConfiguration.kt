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

class HexanaSuiteRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<HexanaSuiteRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): HexanaSuiteRunConfigurationOptions =
        super.getOptions() as HexanaSuiteRunConfigurationOptions

    /** Suite file path, or a directory walked recursively for `*.test.js`. */
    var suitePath: String
        get() = options.getSuitePath()
        set(value) { options.setSuitePath(value) }

    /** "role=path" encoded artifact mappings (table form). */
    var artifactValues: List<String>
        get() = options.getArtifactValues()
        set(value) { options.setArtifactValues(value) }

    /** Free-form artifact mappings, one "role=path" per line. */
    var freeFormArtifacts: String
        get() = options.getFreeFormArtifacts()
        set(value) { options.setFreeFormArtifacts(value) }

    var libRoot: String
        get() = options.getLibRoot()
        set(value) { options.setLibRoot(value) }

    var seed: String
        get() = options.getSeed()
        set(value) { options.setSeed(value) }

    /** Per-test timeout in milliseconds as text; empty = engine default (5000). */
    var testTimeoutMillis: String
        get() = options.getTestTimeoutMillis()
        set(value) { options.setTestTimeoutMillis(value) }

    /**
     * Describe-path filter for rerun-failed: the set of full test paths
     * (describe names + test name, suite file excluded) to execute; everything
     * else is reported as SKIP by the engine. Not persisted: set programmatically
     * on a clone by the rerun-failed action only.
     */
    var testPathFilter: Set<List<String>>? = null

    override fun checkConfiguration() {
        val sp = suitePath
        if (sp.isBlank()) throw RuntimeConfigurationError("Suite path is not specified")
        if (!File(sp).exists()) throw RuntimeConfigurationError("Suite file or directory not found: $sp")
    }

    override fun getConfigurationEditor(): SettingsEditor<HexanaSuiteRunConfiguration> =
        HexanaSuiteRunSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        HexanaSuiteCommandState(this, environment)
}
