package com.cyeam.seal.update

import com.cyeam.seal.BuildConfig
import com.cyeam.seal.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class AppUpdate(val versionName: String, val versionCode: Long, val notes: String, val downloadUrl: String, val sizeBytes: Long)
class UpdateException(val messageId: Int) : Exception()
object AppUpdateClient {
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val endpoint = URL(BuildConfig.APP_UPDATE_URL + "?versionCode=" + BuildConfig.VERSION_CODE)
        val c = endpoint.openConnection() as HttpURLConnection
        try {
            c.connectTimeout=10_000; c.readTimeout=20_000; c.instanceFollowRedirects=false
            if(c.responseCode != 200) {
                val body=c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw UpdateException(if(c.responseCode==404 && runCatching { JSONObject(body).optString("error") }.getOrNull()=="app_not_found") R.string.update_unconfigured else R.string.update_error)
            }
            parse(c.inputStream.bufferedReader().use { it.readText() },endpoint,BuildConfig.VERSION_CODE.toLong(),BuildConfig.APPLICATION_ID)
        } finally { c.disconnect() }
    }
    internal fun parse(body: String, endpoint: URL, installed: Long, packageName: String): AppUpdate? {
        val json = JSONObject(body)
        if (!json.getBoolean("hasUpdate")) return null
        val path=endpoint.path.removeSuffix("/update")
        require(json.getString("appId")==path.substringAfterLast('/'))
        val code=json.getLong("versionCode")
        require(code>installed && json.getString("packageName")==packageName)
        val download=URL(endpoint,json.getString("downloadUrl"))
        require(download.protocol=="https" && download.host==endpoint.host && download.port==endpoint.port && download.userInfo==null && download.path=="$path/download" && download.ref==null)
        require(Instant.parse(json.getString("expiresAt")).isAfter(Instant.now()))
        val size=json.getLong("sizeBytes"); require(size in 1..(200L*1024*1024))
        return AppUpdate(json.getString("versionName"),code,json.optString("releaseNotes"),download.toString(),size)
    }
}
