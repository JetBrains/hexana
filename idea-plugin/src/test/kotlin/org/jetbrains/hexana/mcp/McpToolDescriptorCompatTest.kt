package org.jetbrains.hexana.mcp

import com.intellij.mcpserver.McpToolSchema
import com.intellij.testFramework.UsefulTestCase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

class McpToolDescriptorCompatTest : UsefulTestCase() {
    fun testLiveDescriptorConstructionPreservesBasicFields() {
        val inputSchema = emptySchema()
        val outputSchema = emptySchema()
        val descriptor = hexanaToolDescriptor(
            name = "test_tool",
            description = "Test description",
            fullyQualifiedName = "org.jetbrains.hexana.mcp.HexanaToolset.testTool",
            inputSchema = inputSchema,
            outputSchema = outputSchema,
        )

        assertEquals("test_tool", descriptor.name)
        assertEquals("Test description", descriptor.description)
        assertSame(inputSchema, descriptor.inputSchema)
        assertSame(outputSchema, descriptor.outputSchema)

        val fullyQualifiedName = descriptor.optionalProperty("fullyQualifiedName")
        if (fullyQualifiedName !== MissingProperty) {
            assertEquals("org.jetbrains.hexana.mcp.HexanaToolset.testTool", fullyQualifiedName)
        }

        val category = descriptor.optionalProperty("category")
        if (category != null && category !== MissingProperty) {
            assertEquals("HexanaToolset", category.optionalProperty("shortName"))
            assertEquals("org.jetbrains.hexana.mcp.HexanaToolset", category.optionalProperty("fullyQualifiedName"))
        }
    }

    fun testCategoryClassWithUnsupportedConstructorsIsIgnored() {
        assertNull(instantiateHexanaToolCategory(UnsupportedCategory::class.java))
    }

    fun testPrivateFourArgumentCategoryConstructorIsSupported() {
        val category = instantiateHexanaToolCategory(PrivateFourArgumentCategory::class.java)
            as? PrivateFourArgumentCategory
            ?: error("Expected category instance")

        assertEquals("HexanaToolset", category.shortName)
        assertEquals("org.jetbrains.hexana.mcp.HexanaToolset", category.fullyQualifiedName)
        assertFalse(category.experimental)
        assertTrue(category.alwaysIncluded)
    }

    fun testKotlinDefaultConstructorTailIsSupported() {
        val category = instantiateHexanaToolCategory(KotlinDefaultTailCategory::class.java)
            as? KotlinDefaultTailCategory
            ?: error("Expected category instance")

        assertEquals("HexanaToolset", category.shortName)
        assertEquals("org.jetbrains.hexana.mcp.HexanaToolset", category.fullyQualifiedName)
        assertFalse(category.experimental)
        assertTrue(category.alwaysIncluded)
        assertEquals(0, category.mask)
        assertNull(category.marker)
    }

    fun testDescriptorCategoryAwareConstructorShapeIsSupported() {
        val category = DescriptorCategory("HexanaToolset")
        val inputSchema = emptySchema()
        val outputSchema = emptySchema()
        val descriptor = instantiateDescriptorCandidate<CategoryAwareDescriptor>(
            category = category,
            categoryClass = DescriptorCategory::class.java,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
        ) ?: error("Expected category-aware descriptor")

        assertEquals("test_tool", descriptor.name)
        assertEquals("Test description", descriptor.description)
        assertSame(category, descriptor.category)
        assertEquals("org.jetbrains.hexana.mcp.HexanaToolset.testTool", descriptor.fullyQualifiedName)
        assertSame(inputSchema, descriptor.inputSchema)
        assertSame(outputSchema, descriptor.outputSchema)
    }

    fun testDescriptorKotlinDefaultTailConstructorShapeIsSupported() {
        val category = DescriptorCategory("HexanaToolset")
        val descriptor = instantiateDescriptorCandidate<CategoryAwareDefaultTailDescriptor>(
            category = category,
            categoryClass = DescriptorCategory::class.java,
        ) ?: error("Expected category-aware descriptor with Kotlin default tail")

        assertEquals("test_tool", descriptor.name)
        assertSame(category, descriptor.category)
        assertEquals("org.jetbrains.hexana.mcp.HexanaToolset.testTool", descriptor.fullyQualifiedName)
        assertEquals(0, descriptor.mask)
        assertNull(descriptor.marker)
    }

    fun testDescriptorFallsBackToCategorylessConstructorShape() {
        val inputSchema = emptySchema()
        val outputSchema = emptySchema()
        val descriptor = instantiateDescriptorCandidate<CategorylessDescriptor>(
            category = DescriptorCategory("HexanaToolset"),
            categoryClass = DescriptorCategory::class.java,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
        ) ?: error("Expected categoryless descriptor")

        assertEquals("test_tool", descriptor.name)
        assertEquals("Test description", descriptor.description)
        assertSame(inputSchema, descriptor.inputSchema)
        assertSame(outputSchema, descriptor.outputSchema)
    }

    fun testDescriptorDoesNotTryCategoryAwareShapeWhenCategoryInstantiationFails() {
        val descriptor = instantiateDescriptorCandidate<NullableCategoryDescriptor>(
            category = null,
            categoryClass = DescriptorCategory::class.java,
        )

        assertNull(descriptor)
    }

    private class UnsupportedCategory private constructor(
        @Suppress("unused") val onlyName: String,
    )

    private class PrivateFourArgumentCategory private constructor(
        val shortName: String,
        val fullyQualifiedName: String,
        val experimental: Boolean,
        val alwaysIncluded: Boolean,
    )

    private class KotlinDefaultTailCategory private constructor(
        val shortName: String,
        val fullyQualifiedName: String,
        val experimental: Boolean,
        val alwaysIncluded: Boolean,
        val mask: Int,
        val marker: Any?,
    )

    private data class DescriptorCategory(val shortName: String)

    private class CategoryAwareDescriptor private constructor(
        val name: String,
        val description: String,
        val category: DescriptorCategory,
        val fullyQualifiedName: String,
        val inputSchema: McpToolSchema,
        val outputSchema: McpToolSchema,
    )

    private class CategoryAwareDefaultTailDescriptor private constructor(
        val name: String,
        @Suppress("unused") val description: String,
        val category: DescriptorCategory,
        val fullyQualifiedName: String,
        @Suppress("unused") val inputSchema: McpToolSchema,
        @Suppress("unused") val outputSchema: McpToolSchema,
        val mask: Int,
        val marker: Any?,
    )

    private class CategorylessDescriptor private constructor(
        val name: String,
        val description: String,
        val inputSchema: McpToolSchema,
        val outputSchema: McpToolSchema,
    )

    private class NullableCategoryDescriptor private constructor(
        @Suppress("unused") val name: String,
        @Suppress("unused") val description: String,
        @Suppress("unused") val category: DescriptorCategory?,
        @Suppress("unused") val fullyQualifiedName: String,
        @Suppress("unused") val inputSchema: McpToolSchema,
        @Suppress("unused") val outputSchema: McpToolSchema,
    )

    private inline fun <reified T : Any> instantiateDescriptorCandidate(
        category: DescriptorCategory?,
        categoryClass: Class<*>?,
        inputSchema: McpToolSchema = emptySchema(),
        outputSchema: McpToolSchema = emptySchema(),
    ): T? {
        return descriptorCandidates(
            name = "test_tool",
            description = "Test description",
            category = category,
            categoryClass = categoryClass,
            fullyQualifiedName = "org.jetbrains.hexana.mcp.HexanaToolset.testTool",
            inputSchema = inputSchema,
            outputSchema = outputSchema,
        ).firstNotNullOfOrNull { arguments ->
            instantiateCompatibleConstructor(T::class.java.declaredConstructors, arguments) as? T
        }
    }

    private fun emptySchema(): McpToolSchema {
        return McpToolSchema.ofPropertiesSchema(
            properties = buildJsonObject { },
            requiredProperties = emptySet(),
            definitions = emptyMap<String, JsonElement>(),
        )
    }

    private fun Any.optionalProperty(name: String): Any? {
        javaClass.methods
            .firstOrNull { method -> method.parameterCount == 0 && method.name == name }
            ?.let { method -> return method.invoke(this) }
        javaClass.methods
            .firstOrNull { method ->
                method.parameterCount == 0 && method.name == "get${name.replaceFirstChar(Char::uppercaseChar)}"
            }
            ?.let { method -> return method.invoke(this) }
        return runCatching {
            javaClass.getDeclaredField(name)
                .apply { isAccessible = true }
                .get(this)
        }.getOrDefault(MissingProperty)
    }

    private object MissingProperty
}
