package dev.homecall.homecall

import java.io.File
import java.security.KeyStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LanTransportTest {
    @get:Rule val directory = TemporaryFolder()

    private fun identity(label: String): KeyStore {
        val file = File(directory.root, "$label.p12")
        val password = "temporary-test-key"
        val toolName = if (File.separatorChar == '\\') "keytool.exe" else "keytool"
        val keytool = File(System.getProperty("java.home"), "bin/$toolName")
        val aliases = listOf("homecall-identity-v1", "homecall-tls-v2")
        for (alias in aliases) {
            val process =
                ProcessBuilder(
                        keytool.path,
                        "-genkeypair",
                        "-alias",
                        alias,
                        "-keyalg",
                        "EC",
                        "-groupname",
                        "secp256r1",
                        "-dname",
                        "CN=$label",
                        "-keystore",
                        file.path,
                        "-storetype",
                        "PKCS12",
                        "-storepass",
                        password,
                    )
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        }
        val source =
            KeyStore.getInstance("PKCS12").apply {
                file.inputStream().use { load(it, password.toCharArray()) }
            }
        return KeyStore.getInstance("PKCS12").apply {
            load(null)
            for (alias in aliases) {
                setKeyEntry(
                    alias,
                    source.getKey(alias, password.toCharArray()),
                    null,
                    source.getCertificateChain(alias),
                )
            }
        }
    }

    @Test
    fun helloDiscoversPeerAndPinRejectsAnImpostorBeforeSending() {
        val ip = LanTransport.interfaces().firstOrNull()?.address?.hostAddress
        assumeNotNull(ip)
        val received = LinkedBlockingQueue<String>()
        val server = LanTransport(identity("Kitchen")) { sender, _, _ -> received.offer(sender) }
        val client = LanTransport(identity("Bedroom")) { _, _, _ -> }
        server.name = "Kitchen"
        val hello =
            JSONObject()
                .put("kind", "HELLO")
                .put("from", client.id)
                .put("id", "a".repeat(32))
                .toString()
        server.start()
        try {
            val discovered = client.send(ip!!, null, hello)
            assertEquals(server.id, discovered["id"])
            assertEquals("Kitchen", discovered["name"])
            assertEquals(client.id, received.poll(2, TimeUnit.SECONDS))
            assertEquals(discovered, client.send(ip, server.id, hello))
            assertEquals(client.id, received.poll(2, TimeUnit.SECONDS))
            assertThrows(IllegalArgumentException::class.java) {
                client.send(ip, "0".repeat(64), hello)
            }
            assertTrue(received.isEmpty())
        } finally {
            server.close()
        }
    }
}
