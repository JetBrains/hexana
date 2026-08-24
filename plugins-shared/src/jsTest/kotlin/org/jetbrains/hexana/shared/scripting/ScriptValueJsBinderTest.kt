package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.GenericBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the browser binding of the shared scripting surface — the half that the
 * VS Code webview and the webapp worker rely on.
 */
class ScriptValueJsBinderTest {

    /** Host wrapper stand-in: exposes a fixed set of properties. */
    private fun wrapHost(value: Any): dynamic {
        val wrapped = js("({})")
        wrapped.hostOnly = "from-raw"
        wrapped.format = "raw-format"
        wrapped.identity = value.hashCode()
        return wrapped
    }

    private fun binder() = ScriptValueJsBinder(::wrapHost)

    @Test
    fun primitivesCrossTheBoundaryUnchanged() {
        val binder = binder()

        assertEquals("text", binder.toJs(ScriptValue.Str("text")))
        assertEquals(true, binder.toJs(ScriptValue.Bool(true)))
        assertEquals(7, binder.toJs(ScriptValue.Num(7)))
        assertEquals(null, binder.toJs(ScriptValue.Null))
    }

    @Test
    fun longsBecomeJsNumbersRatherThanKotlinObjects() {
        // Kotlin/JS models Long as an object; without narrowing, arithmetic on a
        // byte offset inside a script would produce NaN.
        val value = binder().toJs(ScriptValue.Num(1_234_567_890_123L))

        assertEquals("number", jsTypeOf(value))
        assertEquals(1_234_567_890_123.0, value as Double)
    }

    @Test
    fun objectMembersAreComputedOnceOnFirstAccess() {
        var reads = 0
        val value = binder().toJs(
            ScriptValue.Obj(
                mapOf(
                    "counted" to {
                        reads++
                        ScriptValue.Str("value-$reads")
                    }
                )
            )
        )

        assertEquals(0, reads, "defining a member must not evaluate it")
        assertEquals("value-1", value.counted as String)
        assertEquals("value-1", value.counted as String)
        assertEquals(1, reads, "a member must be memoized after the first read")
    }

    @Test
    fun arrayElementsAreComputedOnlyWhenIndexed() {
        var reads = 0
        val value = binder().toJs(
            ScriptValue.Arr(1_000) { index ->
                reads++
                ScriptValue.Num(index)
            }
        )

        assertEquals(1000, value.length as Int)
        assertEquals(0, reads, "declaring a large array must not build its elements")
        assertEquals(42, value[42] as Int)
        assertEquals(1, reads)
    }

    @Test
    fun functionsReceiveConvertedArgumentsAndReturnConvertedResults() {
        val value = binder().toJs(
            scriptFunction("join", minArgs = 2, maxArgs = 3) { args ->
                ScriptValue.Str(args.string(0, "a") + ":" + args.int(1, "b") + ":" + args.booleanOr(2, false))
            }
        )

        assertEquals("x:5:false", callFunction(value, "x", 5))
        assertEquals("x:5:true", callFunction(value, "x", 5, true))
    }

    @Test
    fun functionArityIsValidatedTheSameWayAsOnTheJvm() {
        val value = binder().toJs(scriptFunction("needsOne", minArgs = 1) { ScriptValue.Null })

        val message = captureError { callFunction(value) }
        assertTrue(message.contains("needsOne"), message)
        assertTrue(message.contains("expects"), message)
    }

    @Test
    fun uncuratedMembersFallThroughToTheHostObject() {
        val value = binder().toJs(
            ScriptValue.Obj(
                members = mapOf("format" to { ScriptValue.Str("curated") }),
                hostFallback = Any(),
            )
        )

        // A curated name wins; anything else resolves against the raw provider.
        assertEquals("curated", value.format as String)
        assertEquals("from-raw", value.hostOnly as String)
    }

    @Test
    fun theWholeBinarySurfaceIsReachableFromScriptSyntax() {
        val provider = GenericBinaryProvider(byteArrayOf(0x48, 0x69, 0x21).toCommonByteBuffer())
        val binary = binder().toJs(scriptBinaryApi(provider, "greeting.bin"))

        assertEquals("binary", binary.format as String)
        assertEquals("greeting.bin", binary.name as String)
        assertEquals(3, binary.size as Int)
        assertEquals(0x48, callFunction(binary.bytes.at, 0))
        assertEquals("48 69 21", callFunction(binary.bytes.hex, 0, 3))
        assertEquals("Hi!", callFunction(binary.bytes.ascii, 0, 3))
        assertEquals(0, binary.structure.length as Int)
        assertEquals(null, binary.sizeAnalysis)
        assertTrue((binary.tabs.length as Int) >= 0)
        // The escape hatch and the fall-through both reach the host wrapper.
        assertEquals("from-raw", binary.raw.hostOnly as String)
        assertEquals("from-raw", binary.hostOnly as String)
    }

    @Test
    fun outputBindingsProduceTheSameLinesAsTheJvm() {
        val lines = mutableListOf<String>()
        val bindings = binder().outputBindings(ScriptOutput { lines += it })

        callFunction(bindings.getValue("print"), "a", 1, true)
        callFunction(bindings.getValue("table"), "Name", "Size")
        callFunction(bindings.getValue("row"), "code", 1024)

        assertEquals(listOf("a 1 true", "Name\tSize", "code\t1024"), lines)
    }

    private fun callFunction(function: dynamic, vararg arguments: Any?): dynamic {
        @Suppress("UNUSED_VARIABLE")
        val target = function

        @Suppress("UNUSED_VARIABLE")
        val args = arguments
        return js("target.apply(null, args)")
    }

    private fun captureError(block: () -> Unit): String =
        try {
            block()
            "no error was thrown"
        } catch (error: Throwable) {
            error.message ?: error.toString()
        }
}
