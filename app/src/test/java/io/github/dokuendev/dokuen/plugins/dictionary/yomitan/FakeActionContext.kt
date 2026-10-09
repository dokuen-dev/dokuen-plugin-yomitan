package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import io.github.dokuendev.dokuenreader.dictionary.CustomActionContext
import io.github.dokuendev.dokuenreader.dictionary.CustomActionResult
import io.github.dokuendev.dokuenreader.dictionary.ImageRequestResult
import io.github.dokuendev.dokuenreader.dictionary.ImageRequestSource

/**
 * A host that answers image requests from [images] (NoImage otherwise) and text requests with
 * [clipboardText], and records them.
 */
class FakeActionContext(
    private val images: Map<ImageRequestSource, ImageRequestResult> = emptyMap(),
    private val clipboardText: String? = null
) : CustomActionContext {
    /** The image requests made, as source and interactive flag, in order. */
    val requests = mutableListOf<Pair<ImageRequestSource, Boolean>>()
    var textRequests = 0
        private set

    override suspend fun requestImage(source: ImageRequestSource, interactive: Boolean): ImageRequestResult {
        requests += source to interactive
        return images[source] ?: ImageRequestResult.NoImage
    }

    override suspend fun requestClipboardText(): String? {
        textRequests++
        return clipboardText
    }
}

suspend fun YomitanDictionaryPluginService.executeAction(
    payload: String,
    context: CustomActionContext = FakeActionContext()
): CustomActionResult = onExecuteCustomAction(payload, context)
