package com.hexana.app

import com.hexana.core.Greeter

fun buildGreeting(name: String?): String = Greeter().greet(name)

fun main() {
    println(buildGreeting("World"))
}
