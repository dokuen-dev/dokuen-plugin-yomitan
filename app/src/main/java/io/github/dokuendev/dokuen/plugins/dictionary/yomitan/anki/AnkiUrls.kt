package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import java.net.URL
import java.net.URLEncoder

/** The URL handling the original gets from the browser's URL parser and URLSearchParams. */
internal object AnkiUrls {
    private const val HEX = "0123456789ABCDEF"

    /**
     * Percent-encodes in [url] what is unsafe to send as-is: spaces, control characters, non-ASCII
     * characters (as UTF-8), and `"`, `<`, `>`, backtick, `{`, `}`, `|`, `\`, and `^`. Existing
     * escapes and the URL's syntax are left alone. The original leaves this to fetch for every URL
     * it requests. Here the values put into URLs are already percent-encoded, with
     * [encodeUriComponent] or [encodeComponent], so this only matters for the parts of a URL that
     * come from elsewhere, such as the text of a custom URL template or a URL a server answered
     * with.
     *
     * This doesn't match a browser's URL parser exactly. For example, a browser leaves `|`
     * unescaped in a query string, so `iiprop=user|url` goes out as `%7C` here. Servers decode
     * both forms identically, so the differences don't matter in practice.
     */
    fun toAscii(url: String): String {
        val result = StringBuilder(url.length)
        var i = 0
        while (i < url.length) {
            val codePoint = url.codePointAt(i)
            val text = String(Character.toChars(codePoint))
            if (codePoint in 0x21..0x7E && text[0] !in "\"<>`{}|\\^") {
                result.append(text)
            } else {
                for (byte in text.toByteArray(Charsets.UTF_8)) {
                    val b = byte.toInt() and 0xFF
                    result.append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
                }
            }
            i += Character.charCount(codePoint)
        }
        return result.toString()
    }

    /**
     * [value] encoded as JavaScript's encodeURIComponent encodes it: everything except ASCII
     * letters, digits, and `-_.!~*'()` is percent-encoded as UTF-8, so the result is safe in a path
     * segment as well as in a query value.
     */
    fun encodeUriComponent(value: String): String {
        val result = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.!~*'()") {
                result.append(c)
            } else {
                result.append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
            }
        }
        return result.toString()
    }

    /** One value encoded as URLSearchParams encodes it (spaces as `+`). */
    fun encodeComponent(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** [fields] in order, encoded as URLSearchParams.toString() encodes them. */
    fun formEncode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (name, value) -> encodeComponent(name) + "=" + encodeComponent(value) }

    /** [url] resolved against [base], as `new URL(url, base).href`. */
    fun resolve(url: String, base: String): String = URL(URL(base), url).toString()
}
