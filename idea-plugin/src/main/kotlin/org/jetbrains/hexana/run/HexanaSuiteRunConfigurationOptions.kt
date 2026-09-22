package org.jetbrains.hexana.run

import com.intellij.execution.configurations.RunConfigurationOptions

class HexanaSuiteRunConfigurationOptions : RunConfigurationOptions() {
    private val suitePath = string("").provideDelegate(this, "suitePath")
    private val artifactValues = list<String>().provideDelegate(this, "artifactValues")
    private val freeFormArtifacts = string("").provideDelegate(this, "freeFormArtifacts")
    private val libRoot = string("").provideDelegate(this, "libRoot")
    private val seed = string("").provideDelegate(this, "seed")
    private val testTimeoutMillis = string("").provideDelegate(this, "testTimeoutMillis")

    /** Suite file path or a directory to walk for `*.test.js`. */
    fun getSuitePath(): String = suitePath.getValue(this) ?: ""
    fun setSuitePath(value: String) { suitePath.setValue(this, value) }

    /**
     * Artifact mappings stored as "role=path" strings.
     * Written by the declaration-driven table form when the suite path is a single file.
     * For directory suite paths the editor uses [getFreeFormArtifacts] instead.
     * Both forms are merged and honored at run time by [parseArtifactEntries].
     */
    fun getArtifactValues(): List<String> = artifactValues.getValue(this).toList()
    fun setArtifactValues(value: List<String>) {
        artifactValues.getValue(this).run { clear(); addAll(value) }
    }

    /** Free-form artifact mappings, one "role=path" per line. */
    fun getFreeFormArtifacts(): String = freeFormArtifacts.getValue(this) ?: ""
    fun setFreeFormArtifacts(value: String) { freeFormArtifacts.setValue(this, value) }

    fun getLibRoot(): String = libRoot.getValue(this) ?: ""
    fun setLibRoot(value: String) { libRoot.setValue(this, value) }

    fun getSeed(): String = seed.getValue(this) ?: ""
    fun setSeed(value: String) { seed.setValue(this, value) }

    /** Per-test timeout in milliseconds, persisted as a string; empty = engine default. */
    fun getTestTimeoutMillis(): String = testTimeoutMillis.getValue(this) ?: ""
    fun setTestTimeoutMillis(value: String) { testTimeoutMillis.setValue(this, value) }
}
