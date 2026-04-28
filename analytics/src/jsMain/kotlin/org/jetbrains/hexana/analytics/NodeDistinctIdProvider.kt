package org.jetbrains.hexana.analytics

// eval('require') is used intentionally: Kotlin/JS `js()` literals cannot reference
// Kotlin parameters after webpack minification, and webpack's static analysis rewrites
// bare `require()` calls.  Using eval bypasses both issues.  The `module` argument is
// always a hardcoded string constant supplied by callers in this file ("fs", "path",
// "crypto"), so there is no code-injection risk.
private fun nodeRequire(module: String): dynamic =
    js("eval('require')(module)")

/**
 * Persists a random UUID to a file on the Node.js filesystem.
 * Falls back to a random in-memory ID if file operations fail.
 */
class NodeDistinctIdProvider(
    private val storagePath: String
) : DistinctIdProvider {

    private val id: String by lazy { loadOrCreate() }

    override fun distinctId(): String = id

    private fun loadOrCreate(): String {
        val fs = nodeRequire("fs")
        val path = nodeRequire("path")
        val crypto = nodeRequire("crypto")

        try {
            val exists: Boolean = fs.existsSync(storagePath) as Boolean
            if (exists) {
                val content = (fs.readFileSync(storagePath, "utf8") as String).trim()
                if (content.isNotEmpty()) return content
            }
        } catch (_: dynamic) {
            // Fall through to create a new ID
        }

        val newId = (crypto.randomUUID() as String)
        try {
            fs.mkdirSync(path.dirname(storagePath), js("({recursive: true})"))
            fs.writeFileSync(storagePath, newId, "utf8")
        } catch (_: dynamic) {
            // Best effort
        }
        return newId
    }
}
