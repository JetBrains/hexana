package org.jetbrains.hexana.run

import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import javax.swing.Icon

/**
 * Represents a single local variable or function parameter retrieved
 * from LLDB's `frame variable` output and displayed in the debugger's
 * Variables pane.
 *
 * Variables that LLDB reports as `<variable not available>` are shown
 * with a descriptive message in the value column. Child members of
 * composite values (structs, tuples) are represented as nested
 * [WasmLocalVariable] nodes.
 */
internal class WasmLocalVariable(
    val name: String,
    private val typeName: String?,
    private val displayValue: String,
    private val children: List<WasmLocalVariable> = emptyList(),
) : XValue() {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(
            null as Icon?,
            typeName,
            displayValue,
            children.isNotEmpty(),
        )
    }

    override fun computeChildren(node: XCompositeNode) {
        val list = XValueChildrenList()
        for (child in children) {
            list.add(child.name, child)
        }
        node.addChildren(list, true)
    }

    companion object {

        /**
         * Parses the output of LLDB's `frame variable` command into a
         * list of [WasmLocalVariable] instances.
         *
         * Typical output formats:
         * ```
         * (int) x = 42
         * (const char *) name = 0x7ff "hello"
         * (MyStruct) s = (field1 = 1, field2 = 2)
         * (unsigned int) y = <variable not available>
         * (WasmtimeVMContext *) __vmctx = 0x000000075ad04aa0
         * ```
         *
         * Multi-line struct values (curly-brace form) are also handled:
         * ```
         * (MyStruct) s = {
         *   field1 = 1
         *   field2 = 2
         * }
         * ```
         */
        internal fun parseFrameVariableOutput(lines: List<String>): List<WasmLocalVariable> {
            val variables = mutableListOf<WasmLocalVariable>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val parsed = parseVariableLine(line)
                if (parsed != null) {
                    // Check if the value ends with '{' indicating a multi-line value
                    if (parsed.displayValue.trimEnd().endsWith("{")) {
                        val braceLines = mutableListOf(parsed.displayValue)
                        i++
                        var depth = 1
                        while (i < lines.size && depth > 0) {
                            val bline = lines[i]
                            for (ch in bline) {
                                if (ch == '{') depth++
                                if (ch == '}') depth--
                            }
                            braceLines.add(bline)
                            i++
                        }
                        val fullValue = braceLines.joinToString("\n").trim()
                        variables.add(
                            WasmLocalVariable(
                                name = parsed.name,
                                typeName = parsed.typeName,
                                displayValue = fullValue,
                            )
                        )
                    } else {
                        variables.add(
                            WasmLocalVariable(
                                name = parsed.name,
                                typeName = parsed.typeName,
                                displayValue = parsed.displayValue,
                            )
                        )
                        i++
                    }
                } else {
                    i++
                }
            }
            return variables
        }

        /**
         * The pattern for a single `frame variable` line:
         * ```
         * (type) name = value
         * ```
         * The type may contain spaces, pointers, generics, etc.
         * The name is a simple identifier (possibly with `::` qualifiers).
         */
        private val FRAME_VAR_PATTERN =
            """\((.+?)\)\s+(.+?)\s+=\s+(.+)""".toRegex(RegexOption.DOT_MATCHES_ALL)

        private data class ParsedVariable(
            val typeName: String,
            val name: String,
            val displayValue: String,
        )

        private fun parseVariableLine(line: String): ParsedVariable? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            val match = FRAME_VAR_PATTERN.find(trimmed) ?: return null
            return ParsedVariable(
                typeName = match.groupValues[1].trim(),
                name = match.groupValues[2].trim(),
                displayValue = match.groupValues[3].trim(),
            )
        }
    }
}
