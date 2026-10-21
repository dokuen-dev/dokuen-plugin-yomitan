package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

/**
 * An HTTP response, with its [status], the [url] it came from after redirects, its Content-Type,
 * and its [body].
 */
class AnkiHttpResponse(val status: Int, val url: String, val contentType: String?, val body: ByteArray) {
    /** Whether the status is 2xx, like a fetch Response's `ok`. */
    val isOk: Boolean get() = status in 200..299

    /** The body decoded as UTF-8, as a fetch Response's `text()` decodes it whatever the charset. */
    fun text(): String = String(body, Charsets.UTF_8)
}

/**
 * The requests the audio sources make, like the original's fetchAnonymous (no cookies or credentials,
 * redirects followed). A request that gets no response throws a [java.io.IOException]. One idle for
 * longer than its timeoutMillis throws a [java.net.SocketTimeoutException], which is an IOException
 * too. Without timeoutMillis, a request may wait indefinitely. A URL that is not a valid http(s) URL
 * throws an [IllegalArgumentException]. Any status, error statuses included, is a response.
 */
interface AnkiHttp {
    fun get(url: String, timeoutMillis: Int? = null): AnkiHttpResponse

    /** POSTs [fields] as an `application/x-www-form-urlencoded` body. */
    fun postForm(url: String, fields: Map<String, String>, timeoutMillis: Int? = null): AnkiHttpResponse
}
