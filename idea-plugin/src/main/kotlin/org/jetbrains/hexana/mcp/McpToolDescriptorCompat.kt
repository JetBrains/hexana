package org.jetbrains.hexana.mcp

import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

private const val MCP_TOOL_CATEGORY_CLASS_NAME = "com.intellij.mcpserver.McpToolCategory"
private const val HEXANA_TOOLSET_CATEGORY_NAME = "HexanaToolset"
private const val HEXANA_TOOLSET_FQN = "org.jetbrains.hexana.mcp.HexanaToolset"

internal fun hexanaToolDescriptor(
    name: String,
    description: String,
    fullyQualifiedName: String,
    inputSchema: McpToolSchema,
    outputSchema: McpToolSchema,
): McpToolDescriptor {
    val categoryClass = findMcpToolCategoryClass()
    val category = categoryClass?.let(::instantiateHexanaToolCategory)
    val constructors = McpToolDescriptor::class.java.declaredConstructors

    instantiateKotlinPrimaryConstructor(
        type = McpToolDescriptor::class.java,
        argumentsByName = mapOf(
            "name" to name,
            "description" to description,
            "category" to category,
            "fullyQualifiedName" to fullyQualifiedName,
            "inputSchema" to inputSchema,
            "outputSchema" to outputSchema,
        ),
    )?.let { return it as McpToolDescriptor }

    descriptorCandidates(
        name = name,
        description = description,
        category = category,
        categoryClass = categoryClass,
        fullyQualifiedName = fullyQualifiedName,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
    ).forEach { arguments ->
        instantiateCompatibleConstructor(constructors, arguments)
            ?.let { return it as McpToolDescriptor }
    }

    error(
        "No supported McpToolDescriptor constructor found. Kotlin primary constructor: " +
            (describeKotlinPrimaryConstructor(McpToolDescriptor::class.java) ?: "unavailable") +
            ". JVM shapes: " +
            constructors.joinToString { constructor ->
                constructor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }
            }
    )
}

private fun findMcpToolCategoryClass(): Class<*>? {
    return try {
        Class.forName(MCP_TOOL_CATEGORY_CLASS_NAME)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }
}

internal fun instantiateHexanaToolCategory(categoryClass: Class<*>): Any? {
    instantiateKotlinPrimaryConstructor(
        type = categoryClass,
        argumentsByName = mapOf(
            "shortName" to HEXANA_TOOLSET_CATEGORY_NAME,
            "fullyQualifiedName" to HEXANA_TOOLSET_FQN,
            "experimental" to false,
            "alwaysIncluded" to true,
        ),
    )?.let { return it }

    val constructors = categoryClass.declaredConstructors
    return categoryCandidates(HEXANA_TOOLSET_CATEGORY_NAME, HEXANA_TOOLSET_FQN)
        .firstNotNullOfOrNull { arguments -> instantiateCompatibleConstructor(constructors, arguments) }
}

internal fun instantiateKotlinPrimaryConstructor(
    type: Class<*>,
    argumentsByName: Map<String, Any?>,
): Any? {
    val constructor = try {
        type.kotlin.primaryConstructor
    } catch (_: LinkageError) {
        null
    } catch (_: RuntimeException) {
        null
    } ?: return null

    val arguments = mutableMapOf<KParameter, Any?>()
    for (parameter in constructor.parameters) {
        if (parameter.kind != KParameter.Kind.VALUE) {
            continue
        }
        val parameterName = parameter.name ?: return null
        if (!argumentsByName.containsKey(parameterName)) {
            if (!parameter.isOptional) {
                return null
            }
            continue
        }

        val argument = argumentsByName.getValue(parameterName)
        if (argument == null && !parameter.type.isMarkedNullable) {
            if (!parameter.isOptional) {
                return null
            }
            continue
        }
        arguments[parameter] = argument
    }

    return try {
        constructor.isAccessible = true
        constructor.callBy(arguments)
    } catch (_: LinkageError) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

internal fun describeKotlinPrimaryConstructor(type: Class<*>): String? {
    val constructor = try {
        type.kotlin.primaryConstructor
    } catch (_: LinkageError) {
        null
    } catch (_: RuntimeException) {
        null
    } ?: return null

    return constructor.parameters
        .filter { parameter -> parameter.kind == KParameter.Kind.VALUE }
        .joinToString(prefix = "(", postfix = ")") { parameter ->
            buildString {
                append(parameter.name ?: "?")
                append(": ")
                append(parameter.type)
                if (parameter.isOptional) {
                    append(" = <default>")
                }
            }
        }
}

internal fun instantiateCompatibleConstructor(
    constructors: Array<Constructor<*>>,
    arguments: List<Any?>,
): Any? {
    return constructors.asSequence()
        .filter { constructor -> constructor.parameterTypes.size == arguments.size }
        .filter { constructor ->
            constructor.parameterTypes.zip(arguments).all { (parameterType, argument) ->
                parameterType.acceptsArgument(argument)
            }
        }
        .firstNotNullOfOrNull { constructor -> instantiateOrNull(constructor, arguments) }
}

internal fun descriptorCandidates(
    name: String,
    description: String,
    category: Any?,
    categoryClass: Class<*>?,
    fullyQualifiedName: String,
    inputSchema: McpToolSchema,
    outputSchema: McpToolSchema,
): List<List<Any?>> {
    val categoryCandidates = if (categoryClass == null || category == null) {
        emptyList()
    } else {
        listOf(
            listOf(name, description, category, fullyQualifiedName, inputSchema, outputSchema),
            listOf(name, null, description, category, fullyQualifiedName, inputSchema, outputSchema, null),
            listOf(name, null, description, fullyQualifiedName, category, inputSchema, outputSchema, null),
        ).flatMap(::withKotlinDefaultConstructorTail)
    }
    val categorylessCandidates = listOf(
        listOf(name, description, inputSchema, outputSchema),
    ).flatMap(::withKotlinDefaultConstructorTail)

    return categoryCandidates + categorylessCandidates
}

private fun categoryCandidates(shortName: String, fullyQualifiedName: String): List<List<Any?>> {
    return listOf(
        listOf(shortName, fullyQualifiedName, false, true),
        listOf(shortName, fullyQualifiedName),
    ).flatMap(::withKotlinDefaultConstructorTail)
}

private fun withKotlinDefaultConstructorTail(arguments: List<Any?>): List<List<Any?>> {
    return listOf(arguments, arguments + listOf(0, null))
}

private fun instantiateOrNull(constructor: Constructor<*>, arguments: List<Any?>): Any? {
    return try {
        constructor.isAccessible = true
        constructor.newInstance(*arguments.toTypedArray())
    } catch (_: IllegalAccessException) {
        null
    } catch (_: InstantiationException) {
        null
    } catch (_: InvocationTargetException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private fun Class<*>.acceptsArgument(argument: Any?): Boolean {
    if (argument == null) {
        return !isPrimitive
    }
    return boxedPrimitiveType().isInstance(argument)
}

private fun Class<*>.boxedPrimitiveType(): Class<*> {
    if (!isPrimitive) {
        return this
    }
    return when (this) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Void.TYPE -> java.lang.Void::class.java
        else -> this
    }
}
