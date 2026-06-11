package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.importer

import android.graphics.BitmapFactory

class AndroidMediaLoader : MediaLoader {
    override suspend fun getImageDetails(bytes: ByteArray, mediaType: String): ImageDetails {
        if (mediaType == "image/svg+xml") {
            // SVGs are XML text and BitmapFactory will fail to decode them.
            // Returning width=0, height=0 is standard and perfectly safe for vector graphics.
            return ImageDetails(bytes, 0, 0)
        }
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return ImageDetails(
            content = bytes,
            width = options.outWidth.coerceAtLeast(0),
            height = options.outHeight.coerceAtLeast(0)
        )
    }
}
