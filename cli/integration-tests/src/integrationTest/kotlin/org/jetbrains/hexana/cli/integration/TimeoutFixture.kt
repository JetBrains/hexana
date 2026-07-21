package org.jetbrains.hexana.cli.integration

object TimeoutFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.singleOrNull() == "child") {
            Thread.sleep(Long.MAX_VALUE)
            return
        }
        if (args.singleOrNull() == "non-reader") {
            Thread.sleep(Long.MAX_VALUE)
            return
        }
        val child = ProcessBuilder(javaLauncher(), "-cp", System.getProperty("java.class.path"), TimeoutFixture::class.java.name, "child").start()
        println(child.pid())
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}
