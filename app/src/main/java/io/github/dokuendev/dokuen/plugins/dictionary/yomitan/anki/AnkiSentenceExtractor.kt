package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

/**
 * Extracts the sentence surrounding a scanned match, a port of the original Yomitan's
 * TextSourceGenerator.extractSentence using its default sentence-parsing options
 * (scan extent 200, termination mode "custom", the default termination characters and quote
 * pairs). The host passes a whole OCR text block as the lookup context, so this is what
 * narrows `{sentence}` and the cloze markers down to the sentence containing the word.
 *
 * Offsets and the scan extent count code points, as the original does.
 *
 * The quote stack is kept exactly as the original keeps it: the matching quote to look for is
 * pushed at the front and compared against the front, but a match pops the back. With one
 * quote open this is an ordinary stack; with one quote nested inside another the wrong entry
 * is removed, the stack never empties, and the scan runs past the sentence terminators on
 * that side. This is reproduced so sentences match the desktop extension's.
 */
object AnkiSentenceExtractor {

    /**
     * [text] is the extracted sentence; [offset] is the code-point index within it where
     * the scanned match starts.
     */
    data class Sentence(val text: String, val offset: Int)

    const val DEFAULT_SCAN_EXTENT = 200

    /** Terminator characters mapped to (includeCharacterAtStart, includeCharacterAtEnd). */
    private val terminatorMap: Map<Int, Pair<Boolean, Boolean>> =
        listOf(".", "!", "?", "．", "。", "！", "？", "…", "︒", "︕", "︖", "︙")
            .associate { it.codePointAt(0) to (false to true) }

    /** Opening quote mapped to (closing quote, includeCharacterAtStart). */
    private val forwardQuoteMap: Map<Int, Pair<Int, Boolean>>

    /** Closing quote mapped to (opening quote, includeCharacterAtEnd). */
    private val backwardQuoteMap: Map<Int, Pair<Int, Boolean>>

    init {
        val quotePairs = listOf("「" to "」", "『" to "』", "\"" to "\"", "'" to "'")
        val forward = HashMap<Int, Pair<Int, Boolean>>()
        val backward = HashMap<Int, Pair<Int, Boolean>>()
        for ((open, close) in quotePairs) {
            val o = open.codePointAt(0)
            val c = close.codePointAt(0)
            forward[o] = c to false
            backward[c] = o to false
        }
        forwardQuoteMap = forward
        backwardQuoteMap = backward
    }

    /**
     * Extracts the sentence around the match occupying UTF-16 indices [matchStart, matchEnd)
     * of [source].
     */
    fun extract(
        source: String,
        matchStart: Int,
        matchEnd: Int,
        extent: Int = DEFAULT_SCAN_EXTENT,
        terminateAtNewlines: Boolean = true
    ): Sentence {
        val codePoints = source.codePoints().toArray()
        val start = source.codePointCount(0, matchStart.coerceIn(0, source.length))
        val end = source.codePointCount(0, matchEnd.coerceIn(matchStart.coerceIn(0, source.length), source.length))

        val startLength = minOf(extent, start)
        val endLength = minOf(maxOf(extent * 2 - startLength, 0), codePoints.size - end)
        val text = codePoints.copyOfRange(start - startLength, end + endLength)
        val textLength = text.size
        val textEndAnchor = textLength - endLength

        var cursorStart = startLength
        var cursorEnd = textEndAnchor

        // Move backward.
        var quoteStack = ArrayDeque<Int>()
        while (cursorStart > 0) {
            var c = text[cursorStart - 1]
            if (c == '\n'.code && terminateAtNewlines) break

            if (quoteStack.isEmpty()) {
                var terminatorInfo = terminatorMap[c]
                if (terminatorInfo != null) {
                    // Include the previous character while it is a terminator included at start.
                    while (terminatorInfo!!.first && cursorStart > 0) {
                        --cursorStart
                        if (cursorStart == 0) break
                        c = text[cursorStart - 1]
                        terminatorInfo = terminatorMap[c] ?: break
                    }
                    break
                }
            }

            var quoteInfo = forwardQuoteMap[c]
            if (quoteInfo != null) {
                if (quoteStack.isEmpty()) {
                    // Include the previous character while it is a quote included at start.
                    while (quoteInfo!!.second && cursorStart > 0) {
                        --cursorStart
                        if (cursorStart == 0) break
                        c = text[cursorStart - 1]
                        quoteInfo = forwardQuoteMap[c] ?: break
                    }
                    break
                } else if (quoteStack.first() == c) {
                    // Removes the last entry, not the matched first one, as the original does.
                    quoteStack.removeLast()
                    --cursorStart
                    continue
                }
            }

            backwardQuoteMap[c]?.let { quoteStack.addFirst(it.first) }
            --cursorStart
        }

        // Move forward.
        quoteStack = ArrayDeque()
        while (cursorEnd < textLength) {
            var c = text[cursorEnd]
            if (c == '\n'.code && terminateAtNewlines) break

            if (quoteStack.isEmpty()) {
                var terminatorInfo = terminatorMap[c]
                if (terminatorInfo != null) {
                    // Include the following character while it is a terminator included at end.
                    while (terminatorInfo!!.second && cursorEnd < textLength) {
                        ++cursorEnd
                        if (cursorEnd == textLength) break
                        c = text[cursorEnd]
                        terminatorInfo = terminatorMap[c] ?: break
                    }
                    break
                }
            }

            var quoteInfo = backwardQuoteMap[c]
            if (quoteInfo != null) {
                if (quoteStack.isEmpty()) {
                    // Include the following character while it is a quote included at end.
                    while (quoteInfo!!.second && cursorEnd < textLength) {
                        ++cursorEnd
                        if (cursorEnd == textLength) break
                        c = text[cursorEnd]
                        // The original looks the next character up in the forward map here.
                        quoteInfo = forwardQuoteMap[c] ?: break
                    }
                    break
                } else if (quoteStack.first() == c) {
                    // Removes the last entry, not the matched first one, as the original does.
                    quoteStack.removeLast()
                    ++cursorEnd
                    continue
                }
            }

            forwardQuoteMap[c]?.let { quoteStack.addFirst(it.first) }
            ++cursorEnd
        }

        // Trim whitespace.
        while (cursorStart < startLength && isWhitespace(text[cursorStart])) ++cursorStart
        while (cursorEnd > textEndAnchor && isWhitespace(text[cursorEnd - 1])) --cursorEnd

        val sentence = String(text, cursorStart, maxOf(cursorEnd - cursorStart, 0))
        return Sentence(sentence, startLength - cursorStart)
    }

    /** Matches JavaScript's String.prototype.trim whitespace set. */
    private fun isWhitespace(codePoint: Int): Boolean = when (codePoint) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        in 0x2000..0x200A -> true
        else -> false
    }
}
