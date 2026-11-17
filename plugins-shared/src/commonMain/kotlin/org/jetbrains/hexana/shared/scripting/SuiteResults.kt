package org.jetbrains.hexana.shared.scripting

/** Synthetic test name used when the collection pass fails before any test runs. */
const val SUITE_COLLECTION_ERROR_TEST_NAME = "(collection)"

/** Kind of a node in a test-suite result tree. */
enum class SuiteNodeKind { SUITE, TEST }

/** Outcome of a suite node. A SUITE node is FAIL when any descendant TEST failed. */
enum class SuiteNodeStatus { PASS, FAIL, SKIP }

/**
 * One `hexana.artifact(role, options)` declaration collected from a suite file.
 * Pure data, shared across all hosts: the IDE reads these to build the role
 * table in the settings editor and to scan which suites match a binary,
 * without providing any role mappings.
 *
 * @param role the artifact role name (first argument of `hexana.artifact`)
 * @param formats declared acceptable format ids (`options.formats` array plus
 *   `options.type` string, in that order); empty means any format
 * @param optional true when `options.optional === true` (an unmapped optional
 *   role resolves to null instead of failing)
 */
data class SuiteArtifactDeclaration(
    val role: String,
    val formats: List<String> = emptyList(),
    val optional: Boolean = false,
)

/**
 * One node of a test-suite run: either a `describe` group (SUITE) or a single
 * `test` (TEST). Pure data, shared across all hosts.
 *
 * @param name the describe/test name, or the suite file name for the root
 * @param durationMillis wall-clock time; TEST nodes measure hooks + body + drain
 * @param failureMessage failure or timeout description, TEST nodes only
 * @param failureStackLine first guest stack line of the failure, when one exists
 * @param sourceFile absolute path of the file where this node was registered;
 *   null when the call site could not be determined
 * @param sourceLine 1-based line number of the registration call; null when unknown
 */
data class SuiteNodeResult(
    val name: String,
    val kind: SuiteNodeKind,
    val status: SuiteNodeStatus,
    val durationMillis: Long,
    val failureMessage: String? = null,
    val failureStackLine: String? = null,
    val children: List<SuiteNodeResult> = emptyList(),
    val sourceFile: String? = null,
    val sourceLine: Int? = null,
) {
    /**
     * Copy with every duration zeroed, recursively. Durations are the one
     * non-deterministic field of a seeded run (Date stays real by design), so
     * determinism tests compare `normalizedForDeterminism()` trees byte for byte.
     *
     * Source locations are deterministic and are kept as-is.
     */
    fun normalizedForDeterminism(): SuiteNodeResult =
        copy(durationMillis = 0, children = children.map { it.normalizedForDeterminism() })
}
