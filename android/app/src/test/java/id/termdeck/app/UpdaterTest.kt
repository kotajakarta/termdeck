package id.termdeck.app

import id.termdeck.app.data.AndroidUpdater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun testSemverComparison() {
        assertEquals(0, AndroidUpdater.compareSemVer("v1.0.0", "1.0.0"))
        assertEquals(0, AndroidUpdater.compareSemVer("1.2.3", "1.2.3"))
        assertTrue(AndroidUpdater.compareSemVer("v1.0.1", "1.0.0") > 0)
        assertTrue(AndroidUpdater.compareSemVer("1.0.0", "1.1.0") < 0)
        assertTrue(AndroidUpdater.compareSemVer("2.0.0", "1.9.9") > 0)
        assertTrue(AndroidUpdater.compareSemVer("v0.2.0", "v0.1.10") > 0)
    }

    @Test
    fun testFindApkAsset() {
        val sampleJson = """
            [
                {
                    "name": "checksums.txt",
                    "browser_download_url": "https://github.com/kotajakarta/termdeck/releases/download/v1.0.0/checksums.txt"
                },
                {
                    "name": "termdeck-release.apk",
                    "browser_download_url": "https://github.com/kotajakarta/termdeck/releases/download/v1.0.0/termdeck-release.apk"
                }
            ]
        """.trimIndent()
        val jsonArray = Json.parseToJsonElement(sampleJson).jsonArray
        val found = AndroidUpdater.findApkAsset(jsonArray)
        assertNotNull(found)
        assertEquals("termdeck-release.apk", found?.second)
        assertEquals("https://github.com/kotajakarta/termdeck/releases/download/v1.0.0/termdeck-release.apk", found?.first)
    }

    @Test
    fun testFindApkAssetNone() {
        val sampleJson = """
            [
                {
                    "name": "source.tar.gz",
                    "browser_download_url": "https://github.com/kotajakarta/termdeck/archive/v1.0.0.tar.gz"
                }
            ]
        """.trimIndent()
        val jsonArray = Json.parseToJsonElement(sampleJson).jsonArray
        val found = AndroidUpdater.findApkAsset(jsonArray)
        assertNull(found)
    }
}
