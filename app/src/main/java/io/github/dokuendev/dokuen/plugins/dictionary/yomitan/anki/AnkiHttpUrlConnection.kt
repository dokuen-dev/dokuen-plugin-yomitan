package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * [AnkiHttp] over [HttpURLConnection]. Redirects are followed here, up to fetch's limit of 20,
 * rather than by HttpURLConnection, which does not follow one from http to https.
 * The idle timeout is both the connect timeout and the read timeout. The read timeout applies
 * to each read, which matches the original's idle timer that restarts whenever data arrives.
 *
 * Every request sends [userAgent]. A browser always sends its own; without a descriptive one,
 * Wikimedia Commons (Lingua Libre and Wiktionary) refuses the request with HTTP 403, under its
 * User-Agent policy.
 */
class AnkiHttpUrlConnection(private val userAgent: String) : AnkiHttp {

    private companion object {
        const val MAX_REDIRECTS = 20
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }

    override fun get(url: String, timeoutMillis: Int?): AnkiHttpResponse =
        request("GET", url, null, timeoutMillis)

    override fun postForm(url: String, fields: Map<String, String>, timeoutMillis: Int?): AnkiHttpResponse =
        request("POST", url, AnkiUrls.formEncode(fields).toByteArray(Charsets.UTF_8), timeoutMillis)

    private fun request(
        initialMethod: String,
        url: String,
        initialBody: ByteArray?,
        timeoutMillis: Int?
    ): AnkiHttpResponse {
        var method = initialMethod
        var body = initialBody
        var current = parse(url)
        repeat(MAX_REDIRECTS + 1) {
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.setRequestProperty("User-Agent", userAgent)
                timeoutMillis?.let {
                    connection.connectTimeout = it
                    connection.readTimeout = it
                }
                body?.let { bytes ->
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                val location = connection.getHeaderField("Location")
                if (status !in REDIRECT_STATUSES || location == null) {
                    return AnkiHttpResponse(status, current.toString(), connection.contentType, bytes)
                }
                current = parse(AnkiUrls.resolve(location, current.toString()))
                // Like browsers, fetch turns a POST into a GET on a 301, 302, or 303.
                if (status == 303 || (status != 307 && status != 308 && method == "POST")) {
                    method = "GET"
                    body = null
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun parse(url: String): URL {
        val parsed = try {
            URL(AnkiUrls.toAscii(url))
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException("Invalid URL: $url", e)
        }
        require(parsed.protocol == "http" || parsed.protocol == "https") { "Not an http(s) URL: $url" }
        return parsed
    }
}
