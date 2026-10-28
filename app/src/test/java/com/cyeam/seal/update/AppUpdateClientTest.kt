package com.cyeam.seal.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.time.Instant

class AppUpdateClientTest {
    private val endpoint = URL("https://www.cyeam.com/api/apps/seal/update?versionCode=14")
    private fun response() = JSONObject().apply {
        put("appId", "seal")
        put("hasUpdate", true)
        put("versionCode", 15)
        put("versionName", "0.1.0") // Display name need not increase.
        put("packageName", "com.cyeam.seal")
        put("releaseNotes", "改进更新")
        put("sizeBytes", 1024)
        put("expiresAt", Instant.now().plusSeconds(900).toString())
        put("downloadUrl", "/api/apps/seal/download?token=test")
    }
    private fun parse(json: JSONObject) = AppUpdateClient.parse(json.toString(), endpoint, 14, "com.cyeam.seal")

    @Test fun noUpdateNeedsNoDownloadMetadata() {
        assertNull(parse(JSONObject().put("hasUpdate", false)))
    }
    @Test fun resolvesRelativeUrlAndUsesVersionCode() {
        val update = parse(response())!!
        assertEquals(15L, update.versionCode)
        assertEquals("https://www.cyeam.com/api/apps/seal/download?token=test", update.downloadUrl)
    }
    @Test fun rejectsWrongAppPackageAndNonNewerVersion() {
        for ((key, value) in listOf("appId" to "awesome-photo", "packageName" to "com.awesomephoto", "versionCode" to 14, "versionCode" to 13, "sizeBytes" to 0)) {
            assertThrows(IllegalArgumentException::class.java) { parse(response().put(key, value)) }
        }
    }
    @Test fun rejectsUntrustedDownloadAddresses() {
        for (url in listOf("http://www.cyeam.com/api/apps/seal/download", "https://example.com/api/apps/seal/download", "https://user@www.cyeam.com/api/apps/seal/download", "https://www.cyeam.com:8443/api/apps/seal/download", "/api/apps/awesome-photo/download?token=test", "/api/apps/seal/download#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { parse(response().put("downloadUrl", url)) }
        }
    }
    @Test fun rejectsExpiredToken() {
        assertThrows(IllegalArgumentException::class.java) {
            parse(response().put("expiresAt", Instant.now().minusSeconds(1).toString()))
        }
    }
}
