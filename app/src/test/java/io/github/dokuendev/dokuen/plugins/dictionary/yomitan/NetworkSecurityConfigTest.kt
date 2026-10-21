package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Plain-HTTP audio URLs (the original's custom URL example is `http://localhost/...`, and local
 * audio servers are plain HTTP) need cleartext traffic, which Android denies by default since API
 * 28. The JVM tests have no such policy, so this pins the configuration that allows it.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkSecurityConfigTest {

    @Test
    fun theBaseConfigPermitsCleartextTraffic() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val parser = app.resources.getXml(R.xml.network_security_config)
        var permitted: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "base-config") {
                permitted =
                    parser.getAttributeValue("http://schemas.android.com/apk/res/android", "cleartextTrafficPermitted")
                        ?: parser.getAttributeValue(null, "cleartextTrafficPermitted")
            }
        }
        assertEquals("true", permitted)
    }

    @Test
    fun theManifestUsesTheConfig() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.exists() }
            .readText()
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
    }
}
