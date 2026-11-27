package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListeningPortsTest {

    @Test
    fun lsofFieldOutput() {
        val output = """
            p4242
            f21
            n127.0.0.1:9229
            f22
            n[::1]:9229
            f23
            n*:53111
            f24
            n127.0.0.1:53111
        """.trimIndent()
        assertEquals(
            listOf(
                ListeningEndpoint("127.0.0.1", 9229),
                ListeningEndpoint("::1", 9229),
                ListeningEndpoint("127.0.0.1", 53111),
            ),
            ListeningPorts.parseLsof(output),
        )
        assertEquals(emptyList(), ListeningPorts.parseLsof(""))
        assertEquals(listOf("-nP", "-a", "-p", "42", "-iTCP", "-sTCP:LISTEN", "-Fn"), ListeningPorts.lsofArguments(42))
    }

    @Test
    fun procNetTcpKeepsTheProcessListeningSockets() {
        val tcp = """
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0100007F:2405 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 111 1 0 100 0 0 10 0
               1: 00000000:CF17 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 222 1 0 100 0 0 10 0
               2: 0100007F:2405 0100007F:D431 01 00000000:00000000 00:00000000 00000000  1000        0 111 1 0 100 0 0 10 0
               3: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 999 1 0 100 0 0 10 0
        """.trimIndent()
        assertEquals(
            listOf(ListeningEndpoint("127.0.0.1", 9221), ListeningEndpoint("127.0.0.1", 53015)),
            ListeningPorts.parseProcNetTcp(tcp, setOf("111", "222")),
        )
        val tcp6 = """
              sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 00000000000000000000000001000000:2405 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 333 1 0 100 0 0 10 0
               1: 0000000000000000FFFF00000100007F:2406 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 444 1 0 100 0 0 10 0
        """.trimIndent()
        assertEquals(
            listOf(ListeningEndpoint("::1", 9221), ListeningEndpoint("127.0.0.1", 9222)),
            ListeningPorts.parseProcNetTcp(tcp6, setOf("333", "444")),
        )
    }

    @Test
    fun socketInodeFromFdLink() {
        assertEquals("12345", ListeningPorts.socketInode("socket:[12345]"))
        assertNull(ListeningPorts.socketInode("/dev/null"))
        assertNull(ListeningPorts.socketInode("anon_inode:[eventpoll]"))
    }

    @Test
    fun netstatIgnoresLocalizedStateColumn() {
        val output = """

            Aktive Verbindungen

              Proto  Lokale Adresse         Remoteadresse          Status           PID
              TCP    0.0.0.0:135            0.0.0.0:0              ABHÖREN          1032
              TCP    127.0.0.1:9229         0.0.0.0:0              ABHÖREN          4242
              TCP    127.0.0.1:9229         127.0.0.1:50123        HERGESTELLT      4242
              TCP    [::]:53111             [::]:0                 ABHÖREN          4242
              UDP    0.0.0.0:5353           *:*                                     4242
        """.trimIndent()
        assertEquals(
            listOf(ListeningEndpoint("127.0.0.1", 9229), ListeningEndpoint("127.0.0.1", 53111)),
            ListeningPorts.parseNetstat(output, 4242),
        )
    }
}
