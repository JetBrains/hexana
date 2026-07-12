package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.providers.BinaryStringRecord
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HexanaScriptRunnerTest {
    @Test
    fun runsInlineScriptWithBinaryGlobalsAndPrint() {
        val result = HexanaScriptRunner().run(
            HexanaScriptSource.Inline(
                """
                print(binary.bytes.size);
                print(binary.bytes.get(1));
                print(binary.strings.length);
                print(binary.strings[0].value + "@" + binary.strings[0].offset);
                print(binary.sizeAnalysis.name + ":" + binary.sizeAnalysis.children[0].size);
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf("5", "97", "1", "hello@1", "root:2"), result.output.lines)
        assertEquals(false, result.output.truncated)
    }

    @Test
    fun runsScriptFromPathOverProvidedProvider() {
        val script = Files.createTempFile("hexana-script-", ".js")
        Files.writeString(script, "print(binary.bytes.lastIndex);")

        val result = HexanaScriptRunner().run(HexanaScriptSource.FilePath(script.toString()), testProvider())

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf("4"), result.output.lines)
        assertEquals(script.fileName.toString(), result.sourceName)
        assertEquals(script.toRealPath().toString(), result.sourcePath)
    }

    @Test
    fun loadAndRunHelperUsesBinaryInputLoaderIdentity() {
        val binary = Files.createTempFile("hexana-binary-", ".bin")
        Files.write(binary, byteArrayOf(1, 2, 3))

        val result = HexanaScriptRunner().run(
            BinaryInput.FilePath(binary.toString()),
            HexanaScriptSource.Inline("print(binary.bytes.size);"),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf("3"), result.output.lines)
        assertEquals("generic", result.binary?.format)
        assertEquals(binary.toString(), result.binary?.path)
    }

    @Test
    fun missingScriptPathReturnsInvalidInput() {
        val result = HexanaScriptRunner().run(HexanaScriptSource.FilePath("/no/such/hexana-script.js"), testProvider())

        assertEquals(HexanaScriptStatus.INVALID_INPUT, result.status)
        assertEquals("FILE_NOT_FOUND", result.diagnostics.single().code)
    }

    @Test
    fun oversizedInlineScriptReturnsInvalidInput() {
        val result = HexanaScriptRunner(
            limits = HexanaScriptLimits(maxInlineScriptBytes = 4)
        ).run(HexanaScriptSource.Inline("print(1);"), testProvider())

        assertEquals(HexanaScriptStatus.INVALID_INPUT, result.status)
        assertEquals("RESOURCE_LIMIT_EXCEEDED", result.diagnostics.single().code)
    }

    @Test
    fun oversizedScriptFileReturnsInvalidInput() {
        val script = Files.createTempFile("hexana-script-large-", ".js")
        Files.writeString(script, "print(1);")

        val result = HexanaScriptRunner(
            limits = HexanaScriptLimits(maxScriptFileBytes = 4)
        ).run(HexanaScriptSource.FilePath(script.toString()), testProvider())

        assertEquals(HexanaScriptStatus.INVALID_INPUT, result.status)
        assertEquals("RESOURCE_LIMIT_EXCEEDED", result.diagnostics.single().code)
    }

    @Test
    fun syntaxErrorReturnsDiagnosticLocation() {
        val result = HexanaScriptRunner().run(HexanaScriptSource.Inline("if ("), testProvider())

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        val diagnostic = result.diagnostics.single()
        assertEquals("SYNTAX_ERROR", diagnostic.code)
        assertEquals("<inline>", diagnostic.sourceName)
        assertNotNull(diagnostic.line)
    }

    @Test
    fun runtimeErrorReturnsDeterministicDiagnostic() {
        val result = HexanaScriptRunner().run(
            HexanaScriptSource.Inline("throw new Error('boom');"),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        val diagnostic = result.diagnostics.single()
        assertEquals("RUNTIME_ERROR", diagnostic.code)
        assertTrue(diagnostic.message.contains("boom"))
    }

    @Test
    fun outputLineLimitTruncatesWithoutFailingScript() {
        val result = HexanaScriptRunner(
            limits = HexanaScriptLimits(maxOutputLines = 2)
        ).run(HexanaScriptSource.Inline("print('a'); print('b'); print('c');"), testProvider())

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf("a", "b"), result.output.lines)
        assertEquals(true, result.output.truncated)
        assertEquals("OUTPUT_TRUNCATED", result.diagnostics.single().code)
    }

    @Test
    fun outputByteLimitTruncatesLongLine() {
        val result = HexanaScriptRunner(
            limits = HexanaScriptLimits(maxOutputBytes = 5)
        ).run(HexanaScriptSource.Inline("print('abcdef');"), testProvider())

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf("abcde"), result.output.lines)
        assertEquals(true, result.output.truncated)
    }

    @Test
    fun outputByteLimitDoesNotSplitSupplementaryCodePoint() {
        val result = HexanaScriptRunner(
            limits = HexanaScriptLimits(maxOutputBytes = 5)
        ).run(HexanaScriptSource.Inline("print('a😀b');"), testProvider())

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf("a😀"), result.output.lines)
        assertEquals(5, result.output.emittedBytes)
        assertEquals(true, result.output.truncated)
    }

    @Test
    fun outputByteLimitDoesNotReturnUnpairedSurrogate() {
        val result = HexanaScriptRunner(
            limits = HexanaScriptLimits(maxOutputBytes = 2)
        ).run(HexanaScriptSource.Inline("print('a😀');"), testProvider())

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        val line = result.output.lines.single()
        assertEquals("a", line)
        assertTrue(line.none(Character::isSurrogate))
        assertEquals(true, result.output.truncated)
    }

    @Test
    fun outputByteLimitTruncatesOversizedPrintedLine() {
        val result = HexanaScriptRunner(
            limits = HexanaScriptLimits(maxOutputBytes = 8)
        ).run(HexanaScriptSource.Inline("print('x'.repeat(200_000));"), testProvider())

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf("xxxxxxxx"), result.output.lines)
        assertEquals(8, result.output.emittedBytes)
        assertEquals(true, result.output.truncated)
    }

    @Test
    fun timeoutStopsLongRunningScript() {
        val result = HexanaScriptRunner(
            limits = HexanaScriptLimits(timeoutMillis = 200)
        ).run(HexanaScriptSource.Inline("while (true) {}"), testProvider())

        assertEquals(HexanaScriptStatus.TIMEOUT, result.status)
        assertEquals("TIMEOUT", result.diagnostics.single().code)
    }

    @Test
    fun timeoutPreservesOutputTruncationDiagnostics() {
        val result = HexanaScriptRunner(
            limits = HexanaScriptLimits(
                timeoutMillis = 200,
                maxOutputLines = 1,
            )
        ).run(HexanaScriptSource.Inline("print('a'); print('b'); while (true) {}"), testProvider())

        assertEquals(HexanaScriptStatus.TIMEOUT, result.status)
        assertEquals(listOf("a"), result.output.lines)
        assertEquals(true, result.output.truncated)
        assertEquals(listOf("OUTPUT_TRUNCATED", "TIMEOUT"), result.diagnostics.map { it.code })
    }

    @Test
    fun interruptedCallerReturnsStructuredResultAndPreservesInterruptedStatus() {
        val bytesAccessed = CountDownLatch(1)
        val resultRef = AtomicReference<HexanaScriptResult?>()
        val errorRef = AtomicReference<Throwable?>()
        val interruptedAfterRun = AtomicBoolean(false)
        val thread = Thread({
            try {
                resultRef.set(
                    HexanaScriptRunner(
                        limits = HexanaScriptLimits(
                            timeoutMillis = 30_000,
                            maxOutputLines = 1,
                        )
                    ).run(
                        HexanaScriptSource.Inline(
                            "print('kept'); print('truncated'); print(binary.bytes.size); while (true) {}"
                        ),
                        LatchingTestBinaryProvider(bytesAccessed),
                    )
                )
                interruptedAfterRun.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                errorRef.set(error)
            }
        }, "hexana-script-interruption-test")

        thread.start()
        assertTrue(bytesAccessed.await(5, TimeUnit.SECONDS), "Script did not reach the running section")
        thread.interrupt()
        thread.join(5_000)

        assertFalse(thread.isAlive, "Runner thread did not finish after interruption")
        errorRef.get()?.let { throw AssertionError("Runner thread failed", it) }
        val result = assertNotNull(resultRef.get())
        assertEquals(HexanaScriptStatus.INTERNAL_ERROR, result.status)
        assertEquals(listOf("kept"), result.output.lines)
        assertEquals(true, result.output.truncated)
        assertEquals(listOf("OUTPUT_TRUNCATED", "INTERRUPTED"), result.diagnostics.map { it.code })
        assertTrue(interruptedAfterRun.get(), "Runner cleared the caller thread interrupt status")
    }

    @Test
    fun executionExceptionRethrowsVirtualMachineErrorCause() {
        val fatal = TestVirtualMachineError()

        val thrown = assertFailsWith<TestVirtualMachineError> {
            HexanaScriptRunner(beforeExecuteInContext = { throw fatal }).run(
                HexanaScriptSource.Inline("print('never');"),
                testProvider(),
            )
        }

        assertSame(fatal, thrown)
    }

    @Test
    fun executionExceptionRethrowsLinkageErrorCause() {
        val fatal = LinkageError("fatal linkage")

        val thrown = assertFailsWith<LinkageError> {
            HexanaScriptRunner(beforeExecuteInContext = { throw fatal }).run(
                HexanaScriptSource.Inline("print('never');"),
                testProvider(),
            )
        }

        assertSame(fatal, thrown)
    }

    @Test
    fun sandboxDeniesHostClassLookupAndHostAccess() {
        assertSandboxAttemptDenied(
            """
            const System = Java.type('java.lang.System');
            print(System.getProperty('user.home'));
            """.trimIndent(),
        )
    }

    @Test
    fun sandboxDeniesFileReadAndLoad() {
        val secret = "hexana-secret-${System.nanoTime()}"
        val secretFile = Files.createTempFile("hexana-script-secret-", ".js")
        Files.writeString(secretFile, "print('$secret');")
        val escapedPath = secretFile.toString().replace("\\", "\\\\").replace("'", "\\'")

        assertSandboxAttemptDenied("print(read('$escapedPath'));", forbiddenOutput = secret)
        assertSandboxAttemptDenied("load('$escapedPath');", forbiddenOutput = secret)
    }

    @Test
    fun sandboxDeniesProcessCreation() {
        val secret = "hexana-process-${System.nanoTime()}"

        assertSandboxAttemptDenied(
            """
            const ProcessBuilder = Java.type('java.lang.ProcessBuilder');
            const process = new ProcessBuilder('/bin/sh', '-c', 'echo $secret').start();
            print(process.getInputStream().readAllBytes());
            """.trimIndent(),
            forbiddenOutput = secret,
        )
    }

    @Test
    fun sandboxDeniesThreadCreation() {
        val secret = "hexana-thread-${System.nanoTime()}"

        assertSandboxAttemptDenied(
            """
            const Thread = Java.type('java.lang.Thread');
            new Thread(function() { print('$secret'); }).start();
            print('thread-started');
            """.trimIndent(),
            forbiddenOutput = secret,
        )
    }

    @Test
    fun sandboxDeniesNativeAccess() {
        assertSandboxAttemptDenied(
            """
            const System = Java.type('java.lang.System');
            System.load('/tmp/libhexana-script-test-does-not-exist.so');
            print('native-loaded');
            """.trimIndent(),
        )
    }

    private fun testProvider(): GenericBinaryProvider = TestBinaryProvider()

    private fun assertSandboxAttemptDenied(
        script: String,
        forbiddenOutput: String? = null,
    ) {
        val result = HexanaScriptRunner().run(HexanaScriptSource.Inline(script), testProvider())

        assertFalse(result.success, "Sandboxed script unexpectedly succeeded with output: ${result.output.lines}")
        forbiddenOutput?.let { secret ->
            assertTrue(
                result.output.lines.none { line -> line.contains(secret) },
                "Sandboxed script exposed forbidden output: ${result.output.lines}",
            )
        }
    }

    private class TestBinaryProvider : GenericBinaryProvider(byteArrayOf(0, 97, 98, 99, 100).toCommonByteBuffer()) {
        override val strings: List<BinaryStringRecord> =
            listOf(BinaryStringRecord(value = "hello", offset = 1, size = 5, index = 0))

        override val sizeAnalysis: BinarySizeNode =
            BinarySizeNode(
                name = "root",
                size = bytes.size,
                offset = 0,
                children = listOf(BinarySizeNode(name = "child", size = 2, offset = 1)),
            )
    }

    private class LatchingTestBinaryProvider(
        private val bytesAccessed: CountDownLatch,
    ) : GenericBinaryProvider(byteArrayOf(0, 97, 98, 99, 100).toCommonByteBuffer()) {
        override val bytes get() = super.bytes.also { bytesAccessed.countDown() }
    }

    private class TestVirtualMachineError : VirtualMachineError("fatal virtual machine error")
}
