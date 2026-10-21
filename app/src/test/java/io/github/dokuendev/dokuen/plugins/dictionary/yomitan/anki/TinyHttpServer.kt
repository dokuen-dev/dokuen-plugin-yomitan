package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal HTTP/1.1 server on 127.0.0.1 for tests. Each request gets one response from
 * [handle]. Without [keepAlive], the connection is then closed; with it, the connection waits for
 * the client's next request. [requests] records every request, in order, and [connections]
 * counts the connections the server accepted.
 */
class TinyHttpServer(
    private val keepAlive: Boolean = false,
    private val handle: (Request) -> Response
) : AutoCloseable {
    class Request(val method: String, val target: String, val headers: Map<String, String>, val body: String)

    class Response(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
        val delayMillis: Long = 0
    )

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${socket.localPort}"
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    private val accepted = AtomicInteger()
    val connections: Int get() = accepted.get()

    init {
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                accepted.incrementAndGet()
                Thread { serve(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(client: Socket) {
        client.use {
            val input = BufferedInputStream(it.getInputStream())
            while (serveOne(it, input) && keepAlive) {
                // Wait for the next request on the same connection.
            }
        }
    }

    /** Answers one request and returns whether the connection is still usable. */
    private fun serveOne(client: Socket, input: InputStream): Boolean {
        try {
            val requestLine = readLine(input) ?: return false
            val parts = requestLine.split(" ")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return false
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val request = Request(parts[0], parts[1], headers, String(body, Charsets.UTF_8))
            requests += request
            val response = handle(request)
            if (response.delayMillis > 0) Thread.sleep(response.delayMillis)
            val head = buildString {
                append("HTTP/1.1 ${response.status} Status\r\n")
                response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Content-Length: ${response.body.size}\r\n")
                if (!keepAlive) append("Connection: close\r\n")
                append("\r\n")
            }
            val output = client.getOutputStream()
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(response.body)
            output.flush()
            return true
        } catch (e: IOException) {
            // The client gave up (for example, on its idle timeout).
        } catch (e: InterruptedException) {
            // The test ended.
        }
        return false
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
            if (b == '\n'.code) return bytes.toString("ISO-8859-1").trimEnd('\r')
            bytes.write(b)
        }
    }

    override fun close() = socket.close()
}
