package org.jetbrains.hexana.cli.scripting

import org.jetbrains.hexana.shared.scripting.SuiteNodeKind
import org.jetbrains.hexana.shared.scripting.SuiteNodeResult
import org.jetbrains.hexana.shared.scripting.SuiteNodeStatus
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a JUnit XML (Ant/Surefire dialect) report for a list of per-file suite results.
 *
 * Format:
 * - One `<testsuites>` root
 * - One `<testsuite>` per file (keyed by file name)
 * - One `<testcase>` per leaf TEST node
 * - `<failure message="...">` child for FAIL nodes
 * - `time` attributes are seconds with 3 decimal places
 */
internal object JUnitXmlWriter {

    /**
     * @param suiteResults list of (file path, root SuiteNodeResult) pairs in run order
     */
    fun write(suiteResults: List<Pair<String, SuiteNodeResult>>, output: Path) {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<testsuites>\n")
        for ((filePath, rootNode) in suiteResults) {
            appendTestsuite(sb, filePath, rootNode)
        }
        sb.append("</testsuites>\n")
        val parent = output.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        Files.writeString(output, sb)
    }

    private fun appendTestsuite(sb: StringBuilder, filePath: String, rootNode: SuiteNodeResult) {
        val tests = collectLeafTests(rootNode)
        val failures = tests.count { it.status == SuiteNodeStatus.FAIL }
        val skipped = tests.count { it.status == SuiteNodeStatus.SKIP }
        val timeSec = formatSeconds(rootNode.durationMillis)
        val suiteName = escapeXml(rootNode.name)
        sb.append("  <testsuite name=\"$suiteName\"")
        sb.append(" tests=\"${tests.size}\"")
        sb.append(" failures=\"$failures\"")
        sb.append(" skipped=\"$skipped\"")
        sb.append(" time=\"$timeSec\"")
        sb.append(">\n")
        for ((testCase, ancestorPath) in collectLeafTestsWithPath(rootNode)) {
            appendTestcase(sb, testCase, ancestorPath)
        }
        sb.append("  </testsuite>\n")
    }

    private fun appendTestcase(
        sb: StringBuilder,
        node: SuiteNodeResult,
        classNamePath: String,
    ) {
        val timeSec = formatSeconds(node.durationMillis)
        val name = escapeXml(node.name)
        val className = escapeXml(classNamePath)
        sb.append("    <testcase name=\"$name\" classname=\"$className\" time=\"$timeSec\"")
        when (node.status) {
            SuiteNodeStatus.PASS -> sb.append("/>\n")
            SuiteNodeStatus.SKIP -> {
                sb.append(">\n")
                sb.append("      <skipped/>\n")
                sb.append("    </testcase>\n")
            }
            SuiteNodeStatus.FAIL -> {
                val rawMessage = node.failureMessage ?: "Test failed"
                val body = buildString {
                    append(rawMessage)
                    node.failureStackLine?.let { append("\n  at $it") }
                }
                sb.append(">\n")
                sb.append("      <failure message=\"${escapeXml(rawMessage)}\">${escapeXml(body)}</failure>\n")
                sb.append("    </testcase>\n")
            }
        }
    }

    private data class TestWithPath(val node: SuiteNodeResult, val classPath: String)

    private fun collectLeafTestsWithPath(root: SuiteNodeResult): List<TestWithPath> {
        val result = mutableListOf<TestWithPath>()
        fun visit(node: SuiteNodeResult, describePath: List<String>) {
            if (node.kind == SuiteNodeKind.TEST) {
                val classname = if (describePath.isEmpty()) root.name else describePath.joinToString(".")
                result += TestWithPath(node, classname)
            } else {
                for (child in node.children) visit(child, describePath + node.name)
            }
        }
        for (child in root.children) visit(child, emptyList())
        return result
    }

    private fun collectLeafTests(root: SuiteNodeResult): List<SuiteNodeResult> {
        val result = mutableListOf<SuiteNodeResult>()
        fun visit(node: SuiteNodeResult) {
            if (node.kind == SuiteNodeKind.TEST) result += node
            else node.children.forEach { visit(it) }
        }
        visit(root)
        return result
    }

    internal fun formatSeconds(millis: Long): String {
        val seconds = millis / 1000L
        val frac = millis % 1000L
        return "${seconds}.${frac.toString().padStart(3, '0')}"
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
