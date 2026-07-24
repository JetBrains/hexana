package org.jetbrains.hexana.cli.integration

import java.nio.file.Path

object TimeoutFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.singleOrNull() == "early-exit") return
        if (args.singleOrNull() == "non-reader") {
            Thread.sleep(Long.MAX_VALUE)
            return
        }
        if (args.firstOrNull() == "stubborn-child") {
            Runtime.getRuntime().addShutdownHook(Thread {
                Thread.sleep(Long.MAX_VALUE)
            })
            println("ready")
            System.out.flush()
            Thread.sleep(Long.MAX_VALUE)
            return
        }

        check(args.singleOrNull() == "tree")
        val child = ProcessBuilder(
            javaLauncher(),
            "-cp",
            System.getProperty("java.class.path"),
            TimeoutFixture::class.java.name,
            "stubborn-child",
        ).start()
        check(child.inputStream.bufferedReader().readLine() == "ready")
        println(child.pid())
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}

private fun javaLauncher(): String = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString()

private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
