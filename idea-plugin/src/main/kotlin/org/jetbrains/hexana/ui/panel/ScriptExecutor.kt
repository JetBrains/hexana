package org.jetbrains.hexana.ui.panel

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.jetbrains.hexana.providers.BinaryProvider
import java.io.File
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Executes a JavaScript file using GraalJS, passing a [BinaryProvider] as the
 * `binary` binding and a `print` function that collects output lines.
 */
internal object ScriptExecutor {

    fun execute(scriptPath: String, binaryProvider: BinaryProvider, onLine: (String) -> Unit) {
        val scriptFile = File(scriptPath)
        if (!scriptFile.exists()) {
            onLine("[Error] Script file not found: $scriptPath")
            return
        }

        // GraalJS discovers the JS language implementation via ServiceLoader, which
        // relies on the thread context classloader.  Inside an IntelliJ plugin the
        // TCCL is often the platform classloader and does not see the plugin's JARs,
        // so Context.newBuilder("js").build() can hang or throw.  Temporarily switch
        // the TCCL to the plugin classloader that bundles the GraalJS artifacts.
        val previousCl = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = ScriptExecutor::class.java.classLoader

            val hostAccess = HostAccess.newBuilder(HostAccess.ALL)
                .allowPublicAccess(true)
                .allowAllImplementations(true)
                .allowAllClassImplementations(true)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .allowIterableAccess(true)
                .allowIteratorAccess(true)
                .allowMapAccess(true)
                .allowBufferAccess(true)
                .build()
            val context = Context.newBuilder("js")
                .allowHostAccess(hostAccess)
                .allowHostClassLookup { true }
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build()

            context.use { ctx ->
                val bindings = ctx.getBindings("js")

                // Expose a `print` function that streams lines to the UI.
                bindings.putMember("print", ProxyExecutable { args ->
                    val line = args.joinToString(" ") { it.asString() }
                    onLine(line)
                    null
                })

                // Expose the BinaryProvider as a ProxyObject so that all Kotlin
                // properties are visible to JavaScript regardless of module access.
                bindings.putMember("binary", wrapAsProxy(binaryProvider))

                val source = Source.newBuilder("js", scriptFile)
                    .name(scriptFile.name)
                    .build()
                ctx.eval(source)
            }

        } catch (e: Exception) {
            onLine("[Error] ${e.message}")
        } finally {
            Thread.currentThread().contextClassLoader = previousCl
        }
    }

    /**
     * Wraps any object as a [ProxyObject] that exposes its Kotlin properties
     * to GraalJS. List values are additionally wrapped as [ProxyArray] so that
     * `length` and index access work from JavaScript.
     */
    private fun wrapAsProxy(obj: Any): ProxyObject {
        val props = obj::class.memberProperties.associateBy { it.name }
        val keys = props.keys.toList()

        return object : ProxyObject {
            override fun getMember(key: String): Any? {
                @Suppress("UNCHECKED_CAST")
                val prop = props[key] as? KProperty1<Any, *> ?: return null
                val value = prop.get(obj)
                return wrapValue(value)
            }

            override fun getMemberKeys(): Any = ProxyArray.fromList(keys)

            override fun hasMember(key: String): Boolean = key in props

            override fun putMember(key: String?, value: org.graalvm.polyglot.Value?) {
                // read-only
            }
        }
    }

    private fun wrapValue(value: Any?): Any? {
        if (value == null) return null
        if (value is List<*>) {
            return ProxyArray.fromList(value.map { wrapValue(it) })
        }
        // Wrap complex objects that have Kotlin properties so their fields
        // are also accessible from JS (e.g. BinaryStringRecord.value).
        if (value::class.memberProperties.isNotEmpty() && value !is String && value !is Number && value !is Boolean) {
            return wrapAsProxy(value)
        }
        return value
    }
}
