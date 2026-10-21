package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException

class AnkiHttpUrlConnectionTest {

    private val http = AnkiHttpUrlConnection(userAgent = "TestAgent/1.0 (https://example.org)")

    @Test
    fun everyRequestSendsTheUserAgent() {
        // Wikimedia Commons refuses requests without a descriptive User-Agent (HTTP 403).
        TinyHttpServer { TinyHttpServer.Response(200) }.use { server ->
            http.get("${server.base}/a")
            http.postForm("${server.base}/b", mapOf("x" to "y"))

            assertEquals(
                listOf("TestAgent/1.0 (https://example.org)", "TestAgent/1.0 (https://example.org)"),
                server.requests.map { it.headers["user-agent"] }
            )
        }
    }

    @Test
    fun getFollowsARedirectAndReportsTheFinalResponse() {
        TinyHttpServer { request ->
            when (request.target) {
                "/start" -> TinyHttpServer.Response(302, headers = mapOf("Location" to "/final"))
                else -> TinyHttpServer.Response(200, "hi".toByteArray(), mapOf("Content-Type" to "audio/mpeg"))
            }
        }.use { server ->
            val response = http.get("${server.base}/start")

            assertEquals(200, response.status)
            assertTrue(response.isOk)
            assertEquals("${server.base}/final", response.url)
            assertEquals("audio/mpeg", response.contentType)
            assertArrayEquals("hi".toByteArray(), response.body)
        }
    }

    @Test
    fun anErrorStatusIsAResponseNotAnException() {
        TinyHttpServer { TinyHttpServer.Response(404, "nope".toByteArray()) }.use { server ->
            val response = http.get("${server.base}/missing")

            assertEquals(404, response.status)
            assertFalse(response.isOk)
            assertEquals("nope", response.text())
        }
    }

    @Test
    fun postSendsTheFormAndA303SwitchesToGet() {
        TinyHttpServer { request ->
            if (request.target == "/post") {
                TinyHttpServer.Response(303, headers = mapOf("Location" to "/after"))
            } else {
                TinyHttpServer.Response(200, "done".toByteArray())
            }
        }.use { server ->
            val response = http.postForm("${server.base}/post", linkedMapOf("a" to "x y", "b" to "食"))

            assertEquals("done", response.text())
            val (post, after) = server.requests.toList()
            assertEquals("POST", post.method)
            assertEquals("application/x-www-form-urlencoded", post.headers["content-type"])
            assertEquals("a=x+y&b=%E9%A3%9F", post.body)
            assertEquals("GET", after.method)
            assertEquals("/after", after.target)
        }
    }

    @Test
    fun nonAsciiPathIsSentPercentEncoded() {
        TinyHttpServer { TinyHttpServer.Response(200) }.use { server ->
            http.get("${server.base}/search/食べる")

            assertEquals("/search/%E9%A3%9F%E3%81%B9%E3%82%8B", server.requests.single().target)
        }
    }

    @Test
    fun anIdleResponseTimesOut() {
        TinyHttpServer { TinyHttpServer.Response(200, delayMillis = 3000) }.use { server ->
            assertThrows(SocketTimeoutException::class.java) { http.get("${server.base}/slow", 200) }
        }
    }

    @Test
    fun requestsToTheSameServerShareAConnection() {
        TinyHttpServer(keepAlive = true) { TinyHttpServer.Response(200, "ok".toByteArray()) }.use { server ->
            http.get("${server.base}/a")
            http.postForm("${server.base}/b", mapOf("x" to "y"))
            http.get("${server.base}/c")

            assertEquals(3, server.requests.size)
            assertEquals(1, server.connections)
        }
    }

    @Test
    fun aRedirectKeepsItsConnectionForTheNextHop() {
        TinyHttpServer(keepAlive = true) { request ->
            when (request.target) {
                "/start" -> TinyHttpServer.Response(302, "moved".toByteArray(), mapOf("Location" to "/final"))
                else -> TinyHttpServer.Response(200, "hi".toByteArray())
            }
        }.use { server ->
            assertEquals("hi", http.get("${server.base}/start").text())

            assertEquals(1, server.connections)
        }
    }

    @Test
    fun aTimedOutConnectionIsNotReused() {
        TinyHttpServer(keepAlive = true) { request ->
            TinyHttpServer.Response(200, delayMillis = if (request.target == "/slow") 3000 else 0)
        }.use { server ->
            assertThrows(SocketTimeoutException::class.java) { http.get("${server.base}/slow", 200) }
            http.get("${server.base}/fast")

            assertEquals(2, server.connections)
        }
    }

    @Test
    fun aRefusedConnectionIsAnIOExceptionButNotATimeout() {
        val port = ServerSocket(0).use { it.localPort }
        try {
            http.get("http://127.0.0.1:$port/")
            fail("Expected an IOException")
        } catch (e: IOException) {
            assertFalse(e is SocketTimeoutException)
        }
    }

    @Test
    fun aUrlThatIsNotHttpIsAnIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) { http.get("ftp://example.com/a") }
        assertThrows(IllegalArgumentException::class.java) { http.get("not a url") }
    }
}
