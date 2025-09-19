package org.jetbrains.hexana.tests

import kotlinx.browser.window
import kotlinx.coroutines.await
import org.jetbrains.hexana.io.CommonByteBuffer
import org.khronos.webgl.ArrayBuffer
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.js

// Without request init passed explicitly, js target fails
// this looks like a bug in kotlinx.browser definitions
// TODO: to discuss with the Kotlin team
private val defaultFetchInit: RequestInit = js("{}")

actual suspend fun resourceAsByteBuffer(resourcePath: String): CommonByteBuffer {
    val response: Response = window.fetch("/kotlin/${resourcePath}", defaultFetchInit).await()
    val buffer: ArrayBuffer = response.arrayBuffer().await()
    return CommonByteBuffer(buffer)
}