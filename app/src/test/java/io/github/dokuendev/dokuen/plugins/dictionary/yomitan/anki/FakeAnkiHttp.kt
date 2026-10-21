package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * An [AnkiHttp] that answers each request by its URL from the given routes and records the
 * requests made. A URL without a route fails with a ConnectException, as a request does
 * without a network connection.
 */
class FakeAnkiHttp(vararg routes: Pair<String, Reply>) : AnkiHttp {
    class Reply(
        val status: Int = 200,
        val body: ByteArray = ByteArray(0),
        val contentType: String? = null,
        val finalUrl: String? = null,
        val failure: Exception? = null
    ) {
        companion object {
            fun audio(bytes: ByteArray = byteArrayOf(1, 2, 3), contentType: String? = "audio/mpeg") =
                Reply(body = bytes, contentType = contentType)

            fun text(text: String, finalUrl: String? = null) =
                Reply(body = text.toByteArray(), contentType = "text/html; charset=UTF-8", finalUrl = finalUrl)

            fun status(code: Int) = Reply(status = code)
            fun timeout() = Reply(failure = SocketTimeoutException("Read timed out"))
            fun failing(e: Exception) = Reply(failure = e)
        }
    }

    data class Request(
        val method: String,
        val url: String,
        val fields: Map<String, String>?,
        val timeoutMillis: Int?
    )

    private val routes = routes.toMap()
    val requests = mutableListOf<Request>()

    override fun get(url: String, timeoutMillis: Int?): AnkiHttpResponse {
        requests += Request("GET", url, null, timeoutMillis)
        return respond(url)
    }

    override fun postForm(url: String, fields: Map<String, String>, timeoutMillis: Int?): AnkiHttpResponse {
        requests += Request("POST", url, fields, timeoutMillis)
        return respond(url)
    }

    private fun respond(url: String): AnkiHttpResponse {
        val reply = routes[url] ?: throw ConnectException("No route to $url")
        reply.failure?.let { throw it }
        return AnkiHttpResponse(reply.status, reply.finalUrl ?: url, reply.contentType, reply.body)
    }
}
