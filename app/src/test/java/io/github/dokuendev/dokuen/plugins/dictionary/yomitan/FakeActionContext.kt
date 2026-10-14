package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import io.github.dokuendev.dokuenreader.dictionary.CustomActionContext
import io.github.dokuendev.dokuenreader.dictionary.CustomActionResult
import io.github.dokuendev.dokuenreader.dictionary.ImageRequestResult
import io.github.dokuendev.dokuenreader.dictionary.ImageRequestSource
import io.github.dokuendev.dokuenreader.dictionary.WordReading

/**
 * A host that answers image requests from [images] (NoImage otherwise), text requests with
 * [clipboardText], and readings requests with [readings], and records them.
 */
class FakeActionContext(
    private val images: Map<ImageRequestSource, ImageRequestResult> = emptyMap(),
    private val clipboardText: String? = null,
    private val readings: (String) -> List<WordReading>? = { null }
) : CustomActionContext {
    /** The image requests made, as source and interactive flag, in order. */
    val requests = mutableListOf<Pair<ImageRequestSource, Boolean>>()
    var textRequests = 0
        private set

    /** The texts whose readings were requested, in order. */
    val readingsRequests = mutableListOf<String>()

    /** Every request, in order: "readings", "image:<source>", or "text". */
    val log = mutableListOf<String>()

    override suspend fun requestImage(source: ImageRequestSource, interactive: Boolean): ImageRequestResult {
        requests += source to interactive
        log += "image:$source"
        return images[source] ?: ImageRequestResult.NoImage
    }

    override suspend fun requestClipboardText(): String? {
        textRequests++
        log += "text"
        return clipboardText
    }

    override suspend fun requestReadings(text: String): List<WordReading>? {
        readingsRequests += text
        log += "readings"
        return readings(text)
    }
}

suspend fun YomitanDictionaryPluginService.executeAction(
    payload: String,
    context: CustomActionContext = FakeActionContext()
): CustomActionResult = onExecuteCustomAction(payload, context)
