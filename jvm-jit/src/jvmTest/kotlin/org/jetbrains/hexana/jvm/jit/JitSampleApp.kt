package org.jetbrains.hexana.jvm.jit

/**
 * Tiny program used as the JIT-target subject for
 * [JitDumpIntegrationTest]. Run it under a child JVM with
 * `-agentpath:libjitview.{dylib,so,dll}` — the agent records every
 * compiled method into `mem.userDump` and our [JitFile] parser
 * verifies the dump round-trips.
 *
 * [jitTarget] is the method we expect to appear in the dump: it runs
 * a tight loop and is invoked ~200k times below so default tiered
 * compilation will warm it up to a JIT level.
 */
object JitSampleApp {
    @JvmStatic
    fun main(args: Array<String>) {
        var sum = 0L
        repeat(200_000) { i ->
            sum = sum xor jitTarget(i)
        }
        // Print so the optimiser can't deduce the loop has no observable
        // effect and elide it entirely.
        println("sum=$sum")
    }

    @JvmStatic
    fun jitTarget(n: Int): Long {
        var x = 0L
        for (i in 0..(n and 0xFF)) {
            x = x xor (i.toLong() * 31L + 7L)
        }
        return x
    }
}
