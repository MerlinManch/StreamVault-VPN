package com.streamvault.app.vpn

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class WireGuardPairingServerTest {
    private fun server(save: (String, String) -> Unit = { _, _ -> },
        saved: () -> Unit = {}, expired: () -> Unit = {}, lifetime: Long = 300_000) =
        WireGuardPairingServer(InetAddress.getByName("127.0.0.1"), save, saved, expired, lifetime)

    private fun post(server: WireGuardPairingServer, name: String = "Proton", config: String = "example",
        token: String = server.url.substringAfter("?t=")): Pair<Int, String> {
        val body = listOf("token" to token, "name" to name, "config" to config).joinToString("&") {
            "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }.toByteArray(Charsets.UTF_8)
        return request(server.url.substringBefore("/pair") + "/submit", body)
    }

    private fun request(url: String, body: ByteArray? = null): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            return code to (if (code < 400) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    @Test fun phonePageOffersFileAndPasteWithoutExternalResources() {
        server().use {
            val (code, html) = request(it.url)
            assertEquals(200, code)
            assertTrue(html.contains("type=\"file\""))
            assertTrue(html.contains("<textarea"))
            assertTrue(html.contains("await selected.text()"))
            assertFalse(html.contains("https://"))
        }
    }

    @Test fun unicodeAndWireGuardKeyCharactersSurvivePhoneTransferExactly() {
        var received: Pair<String, String>? = null
        val done = CountDownLatch(1)
        val config = "[Interface]\r\n# Grüße 📺 & + %\r\nPrivateKey = Ab+/==\n"
        server(save = { name, text -> received = name to text }, saved = { done.countDown() }).use {
            assertEquals(200, post(it, "Zürich 📺", config).first)
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals("Zürich 📺" to config, received)
        }
    }

    @Test fun rejectsMissingAndWrongSessionTokens() {
        val count = AtomicInteger()
        server(save = { _, _ -> count.incrementAndGet() }).use {
            assertEquals(403, request(it.url.substringBefore('?')).first)
            assertEquals(403, post(it, token = "wrong").first)
            assertEquals(403, post(it, token = "").first)
            assertEquals(0, count.get())
        }
    }

    @Test fun invalidConfigCanBeCorrectedInSameSessionWithoutExposingSecret() {
        server(save = { _, config -> if (config == "secret") throw IllegalArgumentException(config) }).use {
            val response = post(it, config = "secret")
            assertEquals(400, response.first)
            assertFalse(response.second.contains("secret"))
            assertEquals(200, post(it, config = "corrected").first)
        }
    }

    @Test fun validatesUtf8ByteLimitAndNameBeforeSaving() {
        val count = AtomicInteger()
        server(save = { _, _ -> count.incrementAndGet() }).use {
            assertEquals(400, post(it, name = "a".repeat(65)).first)
            assertEquals(400, post(it, config = "ä".repeat(32_769)).first)
            assertEquals(400, post(it, config = " ").first)
            assertEquals(0, count.get())
            assertEquals(200, post(it, config = "a".repeat(65_536)).first)
            assertEquals(1, count.get())
        }
    }

    @Test fun successInvalidatesSessionAndCannotSaveAgain() {
        val count = AtomicInteger()
        val done = CountDownLatch(1)
        server(save = { _, _ -> count.incrementAndGet() }, saved = { done.countDown() }).use {
            assertEquals(200, post(it).first)
            assertTrue(done.await(2, TimeUnit.SECONDS))
            val result = runCatching { post(it).first }.getOrNull()
            assertTrue(result == null || result == 403)
            assertEquals(1, count.get())
        }
    }

    @Test fun cancellationClosesAnIncompleteClientAndNeverImports() {
        val count = AtomicInteger()
        val server = server(save = { _, _ -> count.incrementAndGet() })
        val url = URL(server.url)
        Socket(url.host, url.port).use { socket ->
            socket.getOutputStream().write("POST /submit HTTP/1.1\r\n".toByteArray())
            server.close()
            assertTrue(runCatching { post(server) }.isFailure)
            assertEquals(0, count.get())
        }
    }

    @Test fun expiryStopsServerWithoutSaving() {
        val expired = CountDownLatch(1)
        val count = AtomicInteger()
        server(save = { _, _ -> count.incrementAndGet() }, expired = { expired.countDown() }, lifetime = 50).use {
            assertTrue(expired.await(2, TimeUnit.SECONDS))
            assertTrue(runCatching { post(it) }.isFailure)
            assertEquals(0, count.get())
        }
    }

    @Test fun oversizedRequestIsRejectedBeforeReadingBody() {
        server().use { server ->
            val url = URL(server.url)
            Socket(url.host, url.port).use {
                it.soTimeout = 2_000
                it.getOutputStream().write(("POST /submit HTTP/1.1\r\nHost: localhost\r\n" +
                    "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 600001\r\n\r\n").toByteArray())
                assertTrue(it.getInputStream().bufferedReader().readLine().contains("400"))
            }
        }
    }
}
