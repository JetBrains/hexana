package org.jetbrains.hexana.run

import com.intellij.openapi.components.*

/**
 * Application-level persistent settings for WebAssembly runtime
 * executable paths (Wasmtime, WAMR/iwasm, LLDB).
 *
 * These defaults are inherited by [WasmRunConfiguration] instances
 * when their per-configuration path fields are left blank.
 */
@Service
@State(name = "WasmRuntimeSettings", storages = [Storage("hexana-wasm-runtime.xml", roamingType = RoamingType.PER_OS)])
class WasmRuntimeSettings : SimplePersistentStateComponent<WasmRuntimeSettingsState>(WasmRuntimeSettingsState()) {

    companion object {
        /** Sentinel value stored when the user wants to see the popup every time. */
        const val ASK_EVERY_TIME: String = "ASK_EVERY_TIME"

        @JvmStatic
        fun getInstance(): WasmRuntimeSettings = service()
    }

    /**
     * User-configured Wasmtime path for UI binding. `null` when
     * not explicitly set (falls back to auto-detected default).
     */
    var wasmtimePathInUi: String?
        get() = state.wasmtimePath
        set(value) { state.wasmtimePath = value?.takeIf { it.isNotBlank() } }

    /** User-configured WAMR (iwasm) path for UI binding. */
    var wamrPathInUi: String?
        get() = state.wamrPath
        set(value) { state.wamrPath = value?.takeIf { it.isNotBlank() } }

    /** User-configured GraalVM home path for UI binding. */
    var graalVmPathInUi: String?
        get() = state.graalVmPath
        set(value) { state.graalVmPath = value?.takeIf { it.isNotBlank() } }

    /** User-configured Node.js path for UI binding. */
    var nodePathInUi: String?
        get() = state.nodePath
        set(value) { state.nodePath = value?.takeIf { it.isNotBlank() } }

    /** User-configured JDK home path for running Chicory. */
    var chicoryPathInUi: String?
        get() = state.chicoryPath
        set(value) { state.chicoryPath = value?.takeIf { it.isNotBlank() } }

    /** User-configured LLDB path for UI binding. */
    var lldbPathInUi: String?
        get() = state.lldbPath
        set(value) { state.lldbPath = value?.takeIf { it.isNotBlank() } }

    /** User-configured Chrome/Chromium path for UI binding. */
    var chromePathInUi: String?
        get() = state.chromePath
        set(value) { state.chromePath = value?.takeIf { it.isNotBlank() } }

    /**
     * The default engine selection. `null` or [ASK_EVERY_TIME]
     * means the runtime selection popup is shown on every run.
     * Otherwise holds the [WasmEngine.name] of the chosen default.
     */
    var defaultEngineInUi: String?
        get() = state.defaultEngine
        set(value) { state.defaultEngine = value }

    /**
     * Returns the configured default [WasmEngine], or `null` when
     * the user wants to be prompted every time.
     */
    fun resolveDefaultEngine(): WasmEngine? {
        val raw = state.defaultEngine
        if (raw.isNullOrBlank() || raw == ASK_EVERY_TIME) return null
        return WasmEngine.entries.firstOrNull { it.name == raw }
    }

    /**
     * Returns the effective Wasmtime executable path, considering
     * the user setting, well-known installation locations, and
     * finally falling back to a bare command name for PATH lookup.
     */
    fun effectiveWasmtimePath(): String {
        val configured = state.wasmtimePath
        if (!configured.isNullOrBlank()) return configured
        return WasmtimeCommandLineState.resolveDefaultWasmtimePath()
    }

    /**
     * Returns the effective WAMR (iwasm) executable path.
     */
    fun effectiveWamrPath(): String {
        val configured = state.wamrPath
        if (!configured.isNullOrBlank()) return configured
        return WamrCommandLineState.resolveDefaultWamrPath()
    }

    /**
     * Returns the user-configured GraalVM home path, or `null`
     * when not set (meaning the built-in GraalWasm engine is used).
     */
    fun effectiveGraalVmPath(): String? {
        val configured = state.graalVmPath
        return configured?.takeIf { it.isNotBlank() }
    }

    /**
     * Returns the effective Node.js executable path.
     */
    fun effectiveNodePath(): String {
        val configured = state.nodePath
        if (!configured.isNullOrBlank()) return configured
        return NodeJsCommandLineState.resolveDefaultNodePath()
    }

    /**
     * Returns the user-configured JDK home path for Chicory, or `null`
     * when not set (meaning the IntelliJ-bundled JVM is used).
     */
    fun effectiveChicoryJdkPath(): String? {
        val configured = state.chicoryPath
        return configured?.takeIf { it.isNotBlank() }
    }

    /**
     * Returns the effective LLDB executable path.
     */
    fun effectiveLldbPath(): String {
        val configured = state.lldbPath
        if (!configured.isNullOrBlank()) return configured
        return WasmDebugRunner.resolveDefaultLldbPath()
    }

    /**
     * Returns the effective Chrome/Chromium executable path.
     */
    fun effectiveChromePath(): String {
        val configured = state.chromePath
        if (!configured.isNullOrBlank()) return configured
        return CdpDebugRunner.resolveDefaultChromePath()
    }
}

class WasmRuntimeSettingsState : BaseState() {
    var wasmtimePath: String? by string()
    var wamrPath: String? by string()
    var graalVmPath: String? by string()
    var nodePath: String? by string()
    var chicoryPath: String? by string()
    var lldbPath: String? by string()
    var chromePath: String? by string()
    var defaultEngine: String? by string()
}
