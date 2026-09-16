package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.genericProvider
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.member
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.obj
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.str
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.string
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.wasmProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Covers the global assembly every host binds, and the `open(path)` global in it. */
class ScriptGlobalsTest {

    private fun openFunction(policy: ScriptOpenPolicy = ScriptOpenPolicy.Unsupported): ScriptValue.Fn =
        globals(policy)[SCRIPT_OPEN_GLOBAL] as ScriptValue.Fn

    private fun inlineOpen(opener: ScriptBinaryOpener): ScriptValue.Fn =
        openFunction(ScriptOpenPolicy.Inline(opener))

    private fun globals(policy: ScriptOpenPolicy = ScriptOpenPolicy.Unsupported): Map<String, ScriptValue> =
        scriptGlobals(
            provider = genericProvider(byteArrayOf(1, 2, 3)),
            output = ScriptOutput {},
            fileName = "open.bin",
            open = policy,
        )

    @Test
    fun everyGlobalIsBoundUnderItsOwnName() {
        assertEquals(listOf("binary", "open", "print", "table", "row"), globals().keys.toList())
    }

    @Test
    fun openReturnsTheSameShapeAsBinary() {
        // Returning `binary`'s shape is the point: a comparison script needs no
        // second API to learn. This must not drift into a smaller object.
        val opened = inlineOpen { ScriptOpenedBinary(wasmProvider(), "other.wasm") }
            .invoke(listOf(str("/tmp/other.wasm")))

        assertEquals(
            ScriptApiReference.shape(ScriptApiReference.BINARY_SHAPE).memberNames.sorted(),
            obj(opened).members.keys.sorted(),
        )
        assertEquals("wasm", string(member(opened, "format")))
        assertEquals("other.wasm", string(member(opened, "name")))
    }

    @Test
    fun openPassesThePathToTheHostVerbatim() {
        // Resolving a path is the host's decision, together with whether it is
        // allowed at all, so nothing here may normalize it first.
        val requested = mutableListOf<String>()
        val open = inlineOpen { path ->
            requested += path
            ScriptOpenedBinary(genericProvider(byteArrayOf(9)))
        }

        open.invoke(listOf(str("relative/../binary.bin")))

        assertEquals(listOf("relative/../binary.bin"), requested)
    }

    @Test
    fun openWithoutAHostOpenerReportsWhereItWorksInstead() {
        // In a browser an unbound `open` would resolve to `window.open`, so the
        // global exists in every host and fails loudly where it cannot work.
        val failure = assertFailsWith<UnsupportedOperationException> {
            openFunction().invoke(listOf(str("/tmp/other.wasm")))
        }

        val message = failure.message ?: ""
        assertTrue(message.contains("/tmp/other.wasm"), message)
        assertTrue(message.contains("run-script CLI"), message)
    }

    @Test
    fun aDeferredHostGetsTheRequestToFulfilRatherThanABinary() {
        // The VS Code webview: it cannot read a file inside the call, so `open`
        // evaluates to the request its script driver answers and feeds back.
        val requested = openFunction(ScriptOpenPolicy.Deferred).invoke(listOf(str("/tmp/other.wasm")))

        assertEquals(
            "/tmp/other.wasm",
            string(member(requested, SCRIPT_OPEN_REQUEST_MEMBER)),
        )
    }

    @Test
    fun aDeferredRequestThatNeverReachedADriverExplainsItself() {
        // Reading anything else off the request means the call sat where the
        // script could not pause for it, and saying so beats handing the script
        // an object with no members.
        val requested = obj(openFunction(ScriptOpenPolicy.Deferred).invoke(listOf(str("/tmp/other.wasm"))))
        val blocked = requested.hostFallback as ScriptOpenRequestBlocked

        assertEquals("/tmp/other.wasm", blocked.path)
        assertTrue(blocked.message.contains("top level"), blocked.message)
        assertTrue(blocked.message.contains("/tmp/other.wasm"), blocked.message)
    }

    @Test
    fun everyPolicyValidatesTheArgumentTheSameWay() {
        // The failure text is shared so a bad call reads the same in every host.
        for (policy in listOf(
            ScriptOpenPolicy.Unsupported,
            ScriptOpenPolicy.Deferred,
            ScriptOpenPolicy.Inline { ScriptOpenedBinary(genericProvider(byteArrayOf(1))) },
        )) {
            assertFailsWith<IllegalArgumentException>("policy ${policy::class.simpleName}") {
                openFunction(policy).invoke(emptyList())
            }
        }
    }

    @Test
    fun openRequiresExactlyOnePathArgument() {
        val open = inlineOpen { ScriptOpenedBinary(genericProvider(byteArrayOf(1))) }

        assertFailsWith<IllegalArgumentException> { open.invoke(emptyList()) }
        assertFailsWith<IllegalArgumentException> { open.invoke(listOf(str("a"), str("b"))) }
    }
}
