package com.streamvault.app.vpn

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One-use local phone import. No credentials in URLs, logs or responses. */
internal class WireGuardPairingServer(
    address: InetAddress,
    private val save: (String, String) -> Unit,
    private val saved: () -> Unit,
    private val expired: () -> Unit,
    lifetimeMs: Long = 300_000,
) : Closeable {
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 4, address)
    private val executor = Executors.newScheduledThreadPool(2)
    private val lock = Any()
    @Volatile private var active = true
    @Volatile private var client: Socket? = null
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lifetimeMs)
    val url = "http://${address.hostAddress}:${server.localPort}/pair?t=$token"

    init {
        executor.execute {
            while (active) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                client = socket
                socket.use {
                    if (active) try { handle(it) } catch (_: Exception) {
                        // Incomplete/disconnected requests contain secrets; never log them.
                    }
                }
                client = null
            }
        }
        executor.schedule({
            synchronized(lock) {
                if (active) { close(); expired() }
            }
        }, lifetimeMs, TimeUnit.MILLISECONDS)
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 5_000
        val input = BufferedInputStream(socket.getInputStream())
        val request = readLine(input).split(' ')
        require(request.size == 3)
        val headers = mutableMapOf<String, String>()
        var headerSize = 0
        while (true) {
            val line = readLine(input)
            headerSize += line.length + 2
            require(headerSize <= 8_192)
            if (line.isEmpty()) break
            val key = line.substringBefore(':').lowercase()
            require(key !in headers)
            headers[key] = line.substringAfter(':').trim()
        }
        val output = socket.getOutputStream()
        val path = request[1].substringBefore('?')
        if (request[0] == "GET" && path == "/pair") {
            val supplied = decodeForm(request[1].substringAfter('?', ""))["t"]
            synchronized(lock) {
                if (valid(supplied)) respond(output, 200, WireGuardPairingPage.html(token))
                else respond(output, 403, "QR-Sitzung abgelaufen. Am TV neu starten.")
            }
            return
        }
        if (request[0] != "POST" || path != "/submit") {
            respond(output, 404, "Nicht gefunden."); return
        }
        val length = headers["content-length"]?.toIntOrNull()
        if (headers.containsKey("transfer-encoding") || length == null || length !in 1..MAX_FORM_BYTES ||
            headers["content-type"]?.substringBefore(';') != "application/x-www-form-urlencoded") {
            respond(output, 400, "Ungültige oder zu große Anfrage."); return
        }
        val bytes = ByteArray(length)
        var count = 0
        while (count < length) {
            val read = input.read(bytes, count, length - count)
            require(read > 0)
            count += read
        }
        val form = decodeForm(String(bytes, Charsets.UTF_8))
        synchronized(lock) {
            if (!valid(form["token"])) { respond(output, 403, "QR-Sitzung abgelaufen."); return }
            val name = form["name"].orEmpty().trim().ifBlank { "WireGuard" }
            val config = form["config"].orEmpty()
            if (name.length > 64 || config.isBlank() || config.toByteArray(Charsets.UTF_8).size > 65_536) {
                respond(output, 400, "Name: maximal 64 Zeichen. Konfiguration: 1 bis 64 KiB."); return
            }
            try {
                save(name, config)
            } catch (error: Exception) {
                val message = when ((error as? VpnProfileException)?.reason) {
                    VpnProfileException.Reason.DNS -> "DNS-Server im Abschnitt Interface ergänzen."
                    VpnProfileException.Reason.FULL_TUNNEL -> "Ein Peer mit AllowedIPs = 0.0.0.0/0, bei IPv6 zusätzlich ::/0 erforderlich."
                    VpnProfileException.Reason.INVALID -> "Ungültige WireGuard-Konfiguration. Bitte eine vollständige .conf-Datei verwenden."
                    else -> "Profil konnte nicht gespeichert werden. Konfiguration und Speicher prüfen."
                }
                respond(output, 400, message); return
            }
            // Invalidate before acknowledging: a second POST must never save twice.
            active = false
            try { respond(output, 200, "Profil gespeichert. Am TV jetzt Verbinden wählen. Dieses Fenster kann geschlossen werden.") }
            finally { close(); saved() }
        }
    }

    private fun valid(value: String?) = active && value == token && System.nanoTime() < deadline

    override fun close() {
        synchronized(lock) {
            active = false
            runCatching { server.close() }
            runCatching { client?.close() }
            executor.shutdownNow()
        }
    }

    private fun respond(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val type = if (body.startsWith("<!doctype")) "text/html" else "text/plain"
        output.write(("HTTP/1.1 $status Response\r\nContent-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 8_192) {
            val next = input.read()
            require(next >= 0)
            if (next == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(next)
        }
        error("Header limit")
    }

    private fun decodeForm(body: String): Map<String, String> = body.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to
            URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    companion object { private const val MAX_FORM_BYTES = 600_000 }
}
