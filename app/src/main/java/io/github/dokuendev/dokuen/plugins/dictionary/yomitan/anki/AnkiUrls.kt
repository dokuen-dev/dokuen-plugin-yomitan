package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import java.net.URL
import java.net.URLEncoder

/** The URL handling the original gets from the browser's URL parser and URLSearchParams. */
internal object AnkiUrls {
    private const val HEX = "0123456789ABCDEF"

    /**
     * Percent-encodes in [url] what is unsafe to send as-is: spaces, control characters, non-ASCII
     * characters (as UTF-8), and `"`, `<`, `>`, backtick, `{`, `}`, `|`, `\`, and `^`. Existing
     * escapes and the URL's syntax are left alone. The original builds Jisho, Wikimedia Commons,
     * and custom URLs with the raw term and leaves this to fetch.
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

    /** One value encoded as URLSearchParams encodes it (spaces as `+`). */
    fun encodeComponent(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** [fields] in order, encoded as URLSearchParams.toString() encodes them. */
    fun formEncode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (name, value) -> encodeComponent(name) + "=" + encodeComponent(value) }

    /** [url] resolved against [base], as `new URL(url, base).href`. */
    fun resolve(url: String, base: String): String = URL(URL(base), url).toString()
}
