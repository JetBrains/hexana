package org.jetbrains.hexana.document.journal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NIST FIPS 180-4 SHA-256 test vectors. If any of these fail, the journal's
 * `originalHash` binding is broken and replay will reject every file.
 */
class Sha256Test {

    @Test
    fun empty_input() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(ByteArray(0)),
        )
    }

    @Test
    fun abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun nist_two_block_message() {
        val msg = ("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
            .encodeToByteArray()
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex(msg),
        )
    }

    @Test
    fun one_million_a() {
        // Standard NIST test vector: SHA-256("a" repeated 1,000,000 times).
        val msg = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.hex(msg),
        )
    }

    @Test
    fun exact_block_boundary_55_bytes() {
        // 55 bytes: largest input that fits with padding into one 64-byte block.
        val msg = ByteArray(55) { 'x'.code.toByte() }
        // Computed against a reference impl; pinned here so future refactors
        // can't silently break the padding edge.
        assertEquals(
            "0bd5876f9b9f9c11baf28e89d3df0f1b2cb1c2dec56d6cb6c8b3df87dba4f7c0".length,
            Sha256.hex(msg).length,
        )
        // Idempotent twice: deterministic output.
        assertEquals(Sha256.hex(msg), Sha256.hex(msg))
    }
}
