package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.GenericBinaryProvider
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers cancellation of a webview script run. The driver is a `js(...)` loop
 * rather than a coroutine, so the only thing that can stop it is the flag it is
 * given: without it a tab that was closed, or a run that was replaced by a
 * rerun, keeps stepping its generator to the end of the script.
 */
class ScriptExecutionCancelTest {

    private fun runLoop(isCancelled: (List<String>) -> Boolean): Promise<List<String>> {
        val lines = mutableListOf<String>()
        return executeScriptAsync(
            scriptContent = "for (let i = 0; i < $LINES; i++) print(i);",
            provider = GenericBinaryProvider(byteArrayOf(1, 2, 3).toCommonByteBuffer()),
            fileName = "app.bin",
            filePath = null,
            readBinaryFile = null,
            onLine = { lines += it },
            isCancelled = { isCancelled(lines) },
        ).then { error ->
            assertNull(error, "run reported $error")
            lines.toList()
        }
    }

    @Test
    fun aCancelledRunStopsSteppingTheScript() = runLoop { it.isNotEmpty() }.then { lines ->
        // The flag is read when the driver comes back from the event loop, so the
        // run finishes the batch it was in the middle of and nothing after it.
        assertTrue(lines.isNotEmpty(), "the run never started")
        assertTrue(lines.size < LINES / 10, "the run kept going: ${lines.size} of $LINES lines")
    }

    @Test
    fun aRunNobodyCancelsStillFinishes() = runLoop { false }.then { lines ->
        assertTrue(lines.size == LINES, "only ${lines.size} of $LINES lines arrived")
    }

    private companion object {
        /** Enough batches of `YIELD_EVERY` that stopping early is unmistakable. */
        const val LINES = 5000
    }
}
