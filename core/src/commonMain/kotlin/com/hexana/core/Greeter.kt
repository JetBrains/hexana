package com.hexana.core

class Greeter {
    fun greet(name: String?): String {
        val n = name?.takeIf { it.isNotBlank() }
        return if (n == null) "Hello, world!" else "Hello, $n!"
    }
}
