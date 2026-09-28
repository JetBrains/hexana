package org.jetbrains.hexana.mcp.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json

/** Readable, deterministic changes between two normalized resolved-tool reports. */
class ToolCompatibilityDiff private constructor(
    val differences: List<Difference>,
) {
    enum class Category { Schema, Defaults, Bounds, Binding, Membership, Prose }

    data class Difference(
        val surface: String,
        val view: String,
        val category: Category,
        val path: String,
        val before: JsonElement?,
        val after: JsonElement?,
    )

    fun text(): String = buildString {
        if (differences.isEmpty()) appendLine("No tool compatibility differences.")
        differences.forEach { difference ->
            appendLine(
                "${difference.surface}/${difference.view} ${difference.category.name.lowercase()} " +
                    "${difference.path}: ${value(difference.before)} -> ${value(difference.after)}",
            )
        }
    }

    fun json(): String = JSON.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("differences", buildJsonArray {
            differences.forEach { difference -> add(buildJsonObject {
                put("surface", JsonPrimitive(difference.surface)); put("view", JsonPrimitive(difference.view))
                put("category", JsonPrimitive(difference.category.name.lowercase())); put("path", JsonPrimitive(difference.path))
                put("before", difference.before ?: JsonNull); put("after", difference.after ?: JsonNull)
            }) }
        })
    }) + "\n"

    companion object {
        fun between(before: ResolvedToolCatalogReport, after: ResolvedToolCatalogReport): ToolCompatibilityDiff =
            between(before.document(), after.document())

        fun between(before: JsonObject, after: JsonObject): ToolCompatibilityDiff {
            val changes = mutableListOf<Difference>()
            val beforeTools = tools(before)
            val afterTools = tools(after)
            (beforeTools.keys + afterTools.keys).sorted().forEach { id ->
                val oldTool = beforeTools[id]
                val newTool = afterTools[id]
                if (oldTool == null || newTool == null) {
                    changes += Difference("semantic", "catalog", Category.Membership, "tool/$id", oldTool, newTool)
                } else {
                    listOf("summary", "guidance").forEach { key ->
                        compareJson(changes, "semantic", "catalog", Category.Prose, "tool/$id/$key", oldTool[key], newTool[key])
                    }
                    compareSchema(changes, id, "input", oldTool["inputSchema"].objectOrEmpty(), newTool["inputSchema"].objectOrEmpty())
                    compareSchema(changes, id, "output", oldTool["outputSchema"].objectOrEmpty(), newTool["outputSchema"].objectOrEmpty())
                    compareViews(changes, id, oldTool["views"].objectOrEmpty(), newTool["views"].objectOrEmpty())
                }
            }
            val beforeProducts = cliProducts(before)
            val afterProducts = cliProducts(after)
            (beforeProducts.keys + afterProducts.keys).sorted().forEach { id ->
                compareJson(
                    changes,
                    surface = "cli",
                    view = "product",
                    category = Category.Membership,
                    path = "cliProduct/$id",
                    before = beforeProducts[id],
                    after = afterProducts[id],
                ) { path ->
                    when {
                        path.endsWith("/description") -> Category.Prose
                        path.endsWith("/executableName") -> Category.Binding
                        path.contains("/selection/") && path.endsWith("/command") -> Category.Binding
                        else -> Category.Membership
                    }
                }
            }
            val beforeMcpProducts = mcpProducts(before)
            val afterMcpProducts = mcpProducts(after)
            (beforeMcpProducts.keys + afterMcpProducts.keys).sorted().forEach { id ->
                compareJson(
                    changes,
                    surface = "mcp",
                    view = "product",
                    category = Category.Membership,
                    path = "mcpProduct/$id",
                    before = beforeMcpProducts[id],
                    after = afterMcpProducts[id],
                ) { path ->
                    if (path.endsWith("/defaultProfile") || path.contains("/aliases/")) Category.Binding else Category.Membership
                }
            }
            return ToolCompatibilityDiff(changes)
        }

        private fun tools(document: JsonObject): Map<String, JsonObject> =
            (document["tools"] as? JsonArray).orEmpty().mapNotNull { element ->
                val tool = element as? JsonObject ?: return@mapNotNull null
                (tool["id"] as? JsonPrimitive)?.content?.let { it to tool }
            }.toMap()

        private fun cliProducts(document: JsonObject): Map<String, JsonObject> =
            (document["cliProducts"] as? JsonArray).orEmpty().mapNotNull { element ->
                val product = element as? JsonObject ?: return@mapNotNull null
                (product["id"] as? JsonPrimitive)?.content?.let { it to product }
            }.toMap()

        private fun mcpProducts(document: JsonObject): Map<String, JsonObject> =
            (document["mcpProducts"] as? JsonArray).orEmpty().mapNotNull { element ->
                val product = element as? JsonObject ?: return@mapNotNull null
                (product["id"] as? JsonPrimitive)?.content?.let { it to product }
            }.toMap()

        private fun compareSchema(changes: MutableList<Difference>, toolId: String, schemaName: String, before: JsonObject, after: JsonObject) {
            compareJson(changes, "semantic", schemaName, Category.Schema, "tool/$toolId/$schemaName", before, after) {
                categoryForSchemaKey(it.substringAfterLast('/'))
            }
        }

        private fun compareViews(changes: MutableList<Difference>, toolId: String, before: JsonObject, after: JsonObject) {
            (before.keys + after.keys).sorted().forEach { name ->
                val oldView = before[name] as? JsonObject
                val newView = after[name] as? JsonObject
                val surface = when {
                    name.startsWith("standalone") -> "standalone"
                    else -> name
                }
                compareJson(changes, surface, name, Category.Membership, "tool/$toolId/view/$name/selected", oldView?.get("selected"), newView?.get("selected"))
                compareJson(changes, surface, name, Category.Binding, "tool/$toolId/view/$name/details", oldView?.get("details"), newView?.get("details")) { path ->
                    when {
                        path.contains("/products") -> Category.Membership
                        path.endsWith("/description") || path.endsWith("/explanation") ||
                            path.endsWith("/details/examples") || path.contains("/details/example/") -> Category.Prose
                        path.endsWith("/default") -> Category.Defaults
                        path.endsWith("/constraints") -> Category.Bounds
                        else -> Category.Binding
                    }
                }
            }
        }

        private fun compareJson(
            changes: MutableList<Difference>, surface: String, view: String, category: Category, path: String,
            before: JsonElement?, after: JsonElement?, categoryAt: (String) -> Category = { category },
        ) {
            if (before == after) return
            if (before is JsonObject && after is JsonObject) {
                (before.keys + after.keys).sorted().forEach { key ->
                    compareJson(changes, surface, view, category, "$path/$key", before[key], after[key], categoryAt)
                }
            } else if (before is JsonArray && after is JsonArray) {
                val key = arrayKey(path)
                val oldItems = indexedArray(before, key)
                val newItems = indexedArray(after, key)
                (oldItems.keys + newItems.keys).sorted().forEach { item ->
                    val oldItem = oldItems[item]
                    val newItem = newItems[item]
                    val itemPath = if (key == "member") "$path/$item" else "${path.substringBeforeLast('/')}/$key/$item"
                    if (oldItem is JsonObject && newItem is JsonObject) {
                        compareJson(changes, surface, view, category, itemPath, oldItem, newItem, categoryAt)
                    } else if (oldItem != newItem) {
                        val categoryPath = if (path.startsWith("mcpProduct/") && path.endsWith("/aliases")) itemPath else path
                        changes += Difference(
                            surface, view, categoryAt(categoryPath), itemPath, oldItem, newItem,
                        )
                    }
                }
            } else {
                changes += Difference(surface, view, categoryAt(path), path, before, after)
            }
        }

        private fun categoryForSchemaKey(key: String): Category = when (key) {
            "default" -> Category.Defaults
            "minimum", "maximum", "maxItems", "maxLength", "itemMinimum", "itemMaximum" -> Category.Bounds
            "oneOf", "inputCapabilities", "outputCapabilities" -> Category.Membership
            "description" -> Category.Prose
            else -> Category.Schema
        }

        private fun arrayKey(path: String): String = when (path.substringAfterLast('/')) {
            "fields" -> "field"
            "sources" -> "source"
            "params" -> "param"
            "inputOptions" -> "option"
            "sourceGroups" -> "source"
            "examples" -> "example"
            "selections" -> if (path.startsWith("mcpProduct/")) "member" else "tool"
            "profiles" -> "profile"
            else -> "member"
        }

        private fun indexedArray(array: JsonArray, key: String): Map<String, JsonElement> = array.mapIndexed { index, element ->
            val identity = when (key) {
                "example" -> index.toString()
                "option" -> (element as JsonObject)["name"]!!.jsonPrimitive.content
                "field" -> (element as JsonObject)["wireName"]!!.jsonPrimitive.content
                "source", "param" -> (element as JsonObject)["name"]!!.jsonPrimitive.content
                "tool" -> (element as JsonObject)["tool"]!!.jsonPrimitive.content
                "profile" -> (element as JsonObject)["id"]!!.jsonPrimitive.content
                else -> (element as JsonPrimitive).content
            }
            identity to element
        }.toMap()

        private fun JsonElement?.objectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
        private fun value(value: JsonElement?): String = when (value) {
            null -> "<absent>"
            JsonNull -> "null"
            else -> value.toString()
        }

        private val JSON = Json { prettyPrint = true }
    }
}
