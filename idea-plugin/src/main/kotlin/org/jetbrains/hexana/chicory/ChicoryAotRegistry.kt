package org.jetbrains.hexana.chicory

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.annotations.ApiStatus

private val LOG = logger<ChicoryAotRegistryImpl>()

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

/**
 * A single Chicory AOT compiler-plugin configuration entry discovered from
 * a Maven or Gradle build model.
 *
 * @property wasmFilePath  path to the WASM file, relative to [baseDir]
 * @property moduleInterface  fully-qualified Java interface implemented by the
 *   generated module (e.g. `io.roastedroot.jq4j.Jq`)
 * @property baseDir  VFS URL of the directory containing the build file, used at
 *   query time to resolve [wasmFilePath] to a [VirtualFile]
 */
/**
 * `var` + defaults are required so IntelliJ's XML serializer can construct
 * instances via the no-arg constructor and populate them through setters.
 * Although we treat these values as effectively immutable at runtime, the
 * persistence framework needs the setters to round-trip through XML.
 */
data class ChicoryAotConfig(
    var wasmFilePath: String = "",
    var moduleInterface: String = "",
    var baseDir: String = "",
)

/** Identifies which build-system populated a batch of [ChicoryAotConfig] entries. */
enum class ConfigSource { MAVEN, GRADLE }

/**
 * The result of a successful [ChicoryAotRegistry.findMatch] lookup.
 *
 * @property wasmFile  the WASM binary file whose AOT-compiled class contains the method
 * @property funcIdx   the function index inside that WASM module (from the `func_N` method name)
 */
data class ChicoryAotMatch(
    val wasmFile: VirtualFile,
    val funcIdx: Int,
)

// ---------------------------------------------------------------------------
// Registry interface
// ---------------------------------------------------------------------------

/**
 * Project-level registry that maps Chicory AOT-generated Java methods back to the
 * originating WASM file and function index.
 *
 * Configs are populated by the optional Maven and Gradle import extensions after
 * each project sync via [setConfigs].  The [findMatch] predicate then walks the
 * union of all populated sources.
 *
 * ### Matching predicate
 *
 * A method matches when:
 * 1. [methodName] is `func_N` for some non-negative integer N — that integer
 *    becomes [ChicoryAotMatch.funcIdx].
 * 2. [classFqn] matches `<package>.<SimpleName>ModuleMachineFuncGroup_\d+` where
 *    `<package>.<SimpleName>` equals the `moduleInterface` stored in a known config.
 *    The trailing `_\d+` suffix is a Chicory-internal group-partitioning index
 *    that is deliberately ignored.
 * 3. The WASM file path recorded in the config resolves to an existing
 *    [VirtualFile] on disk.
 */
interface ChicoryAotRegistry {

    /**
     * Returns a [ChicoryAotMatch] if [classFqn] and [methodName] together identify
     * a Chicory AOT-generated method linked to a known WASM file, or `null` otherwise.
     *
     * Safe to call from a background thread.  Should not be called on the EDT.
     */
    fun findMatch(classFqn: String, methodName: String): ChicoryAotMatch?

    /**
     * Replaces the registry's per-source view atomically with [configs].
     *
     * Called by the Maven and Gradle import extensions after each successful sync.
     * [source] identifies which build system provided the data so the two sources
     * can coexist independently (multi-module projects may use both).
     */
    @ApiStatus.Internal
    fun setConfigs(source: ConfigSource, configs: Collection<ChicoryAotConfig>)

    companion object {
        /**
         * Returns the project-level [ChicoryAotRegistry] instance.
         */
        fun getInstance(project: Project): ChicoryAotRegistry =
            project.service<ChicoryAotRegistryImpl>()
    }
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/** Regex matching Chicory AOT method names: `func_<N>`. Capture group 1 = N. */
private val FUNC_IDX_PATTERN = Regex("""^func_(\d+)$""")

/**
 * Regex matching the generated group-class suffix appended by Chicory:
 * `ModuleMachineFuncGroup_<digit+>`.
 *
 * Example: `io.roastedroot.jq4j.JqModuleMachineFuncGroup_0`
 *   - base interface: `io.roastedroot.jq4j.Jq`
 *   - suffix stripped:  `ModuleMachineFuncGroup_0`
 */
private val GROUP_SUFFIX_REGEX = Regex("""ModuleMachineFuncGroup_\d+$""")

/**
 * Persistent state container. IntelliJ's XML serializer round-trips through
 * the no-arg constructors of [State] and [ChicoryAotConfig]. `var` properties
 * with defaults are required so the framework can build instances reflectively
 * and copy values through setters.
 */
class ChicoryAotRegistryState {
    var maven: MutableList<ChicoryAotConfig> = mutableListOf()
    var gradle: MutableList<ChicoryAotConfig> = mutableListOf()
}

@Service(Service.Level.PROJECT)
@State(
    name = "HexanaChicoryAotRegistry",
    storages = [Storage("hexana-chicory-aot.xml")],
)
internal class ChicoryAotRegistryImpl(private val project: Project) :
    ChicoryAotRegistry,
    PersistentStateComponent<ChicoryAotRegistryState> {

    /**
     * Backing state. Replaced wholesale by [loadState] on IDE startup, mutated
     * in place by [setConfigs] on each Maven / Gradle sync.
     */
    @Volatile
    private var stateRef: ChicoryAotRegistryState = ChicoryAotRegistryState()

    override fun getState(): ChicoryAotRegistryState = stateRef

    override fun loadState(state: ChicoryAotRegistryState) {
        stateRef = state
        LOG.warn("[chicory-debug] loadState maven=${state.maven.size} gradle=${state.gradle.size}")
    }

    override fun setConfigs(source: ConfigSource, configs: Collection<ChicoryAotConfig>) {
        val list = configs.toMutableList()
        when (source) {
            ConfigSource.MAVEN -> stateRef.maven = list
            ConfigSource.GRADLE -> stateRef.gradle = list
        }
        LOG.warn("[chicory-debug] setConfigs source=$source count=${list.size} configs=$list")
    }

    override fun findMatch(classFqn: String, methodName: String): ChicoryAotMatch? {
        // 1. Parse the function index from the method name.
        val funcIdx = FUNC_IDX_PATTERN.find(methodName)?.groupValues?.get(1)?.toIntOrNull()
        if (funcIdx == null) {
            LOG.warn("[chicory-debug] findMatch classFqn=$classFqn methodName=$methodName → null (method name doesn't match func_<N>)")
            return null
        }

        // 2. Derive the `moduleInterface` candidate from the class FQN.
        //    e.g. `io.roastedroot.jq4j.JqModuleMachineFuncGroup_0` → `io.roastedroot.jq4j.Jq`
        val moduleInterface = GROUP_SUFFIX_REGEX.replace(classFqn, "").trimEnd('.')
        if (moduleInterface == classFqn) {
            LOG.warn("[chicory-debug] findMatch classFqn=$classFqn methodName=$methodName → null (class FQN doesn't end in ModuleMachineFuncGroup_<N>)")
            return null
        }

        // 3. Walk the union of all source configs, return first VFS hit.
        val snapshot = stateRef
        val totalConfigs = snapshot.maven.size + snapshot.gradle.size
        LOG.warn("[chicory-debug] findMatch classFqn=$classFqn methodName=$methodName moduleInterfaceDerived=$moduleInterface funcIdx=$funcIdx walking $totalConfigs configs")

        val vfsManager = VirtualFileManager.getInstance()
        for ((source, configs) in listOf(ConfigSource.MAVEN to snapshot.maven, ConfigSource.GRADLE to snapshot.gradle)) {
            for (config in configs) {
                if (config.moduleInterface != moduleInterface) {
                    LOG.warn("[chicory-debug]   skip ($source) config.moduleInterface=${config.moduleInterface} != $moduleInterface")
                    continue
                }
                val wasmVf = resolveWasmFile(config, vfsManager)
                if (wasmVf == null) {
                    LOG.warn("[chicory-debug]   hit but unresolved VFS ($source) config=$config")
                    continue
                }
                LOG.warn("[chicory-debug]   MATCH ($source) config=$config funcIdx=$funcIdx wasmVf=$wasmVf")
                return ChicoryAotMatch(wasmVf, funcIdx)
            }
        }
        LOG.warn("[chicory-debug] findMatch classFqn=$classFqn methodName=$methodName → null (no config matched after walking $totalConfigs)")
        return null
    }

    private fun resolveWasmFile(
        config: ChicoryAotConfig,
        vfsManager: VirtualFileManager,
    ): VirtualFile? {
        // The chicory-compiler-maven-plugin can record `<wasmFile>` as either an
        // absolute path (often interpolated against `${project.basedir}/...`,
        // potentially with `..` segments) or a relative one. Normalise both to
        // a canonical absolute path before going through VFS so a leading `/`
        // or embedded `..` doesn't break the lookup.
        val pathString = config.wasmFilePath
        val resolved: java.nio.file.Path = if (java.io.File(pathString).isAbsolute) {
            java.nio.file.Paths.get(pathString).normalize()
        } else {
            val base = vfsManager.findFileByUrl(config.baseDir) ?: return null
            java.nio.file.Paths.get(base.path, pathString).normalize()
        }
        val canonical = resolved.toString()
        val url = if (canonical.startsWith("/")) "file://$canonical" else "file:///$canonical"
        val direct = vfsManager.findFileByUrl(url)
        if (direct != null) return direct
        // Fallback to the original relative path resolution against baseDir in
        // case the absolute-path branch produced an unexpected URL form.
        val base = vfsManager.findFileByUrl(config.baseDir) ?: return null
        return base.findFileByRelativePath(pathString)
    }
}
