package org.jetbrains.hexana.arrow

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Robustness against malformed / adversarial Arrow inputs.
 *
 * `fuzz_tail_only_magic.arrow_file` is an apache/arrow-testing clusterfuzz case
 * (arrow-ipc-file-fuzz-4983476816314368, 1552 B): it carries the `ARROW1` magic only at
 * the TAIL, with a garbage head. A well-formed Arrow IPC file must carry `ARROW1` at BOTH
 * ends, so the reader must reject it fast with a bounded exception — never hang or OOM.
 */
class ArrowFuzzRobustnessTest {

    private fun loadResource(name: String): ByteArray =
        (ArrowFuzzRobustnessTest::class.java.classLoader.getResourceAsStream(name)
            ?: error("Test resource not found: $name")).readBytes()

    @Test
    fun tailOnlyMagic_readerRejectsWithBoundedException() {
        val buf = loadResource("fuzz_tail_only_magic.arrow_file").toCommonByteBuffer()
        // Missing leading ARROW1 magic -> require(...) fails fast; no footer read, no FlatBuffers walk.
        assertFailsWith<IllegalArgumentException> { readArrowFileFooter(buf) }
    }
}