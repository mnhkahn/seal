package com.cyeam.seal.ocr

import com.cyeam.seal.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class Candidate(
    val text: String, val score: Double? = null, val matchType: String = "",
    val component: String = "", val work: String = "", val codepoint: String = "",
    val glyphImage: String = "", val unicodeImage: String = ""
)
class OcrException(val status: Int) : Exception("OCR HTTP $status")

object OcrClient {
    suspend fun recognize(png: ByteArray): List<Candidate> = withContext(Dispatchers.IO) {
        require(png.isNotEmpty() && png.size <= 10 * 1024 * 1024)
        val boundary = "Seal-${UUID.randomUUID()}"
        val connection = URL(BuildConfig.OCR_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 125_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use { out ->
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"glyph.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
                out.write(png)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            if (connection.responseCode != 200) throw OcrException(connection.responseCode)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally { connection.disconnect() }
    }

    fun parse(body: String): List<Candidate> {
        var payload: Any? = JSONTokener(body).nextValue()
        if (payload is JSONObject && payload.has("code")) {
            if (payload.getInt("code") != 0) throw OcrException(502)
            payload = payload.opt("result")
        }
        if (payload is String) payload = JSONTokener(payload).nextValue()
        if (payload is JSONObject && payload.has("matches")) payload = payload.getJSONArray("matches")
        val values = if (payload is JSONArray) (0 until payload.length()).map { payload.opt(it) } else listOf(payload)
        return values.mapNotNull { value ->
            when (value) {
                is String -> value.takeIf { it.isNotBlank() }?.let { Candidate(it) }
                is JSONObject -> {
                    val text = listOf("char", "text", "label", "character", "name", "value")
                        .firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                        ?: return@mapNotNull null
                    val score = listOf("prob", "score", "similarity", "probability", "confidence").firstNotNullOfOrNull { key ->
                        val raw = value.opt(key)?.toString() ?: return@firstNotNullOfOrNull null
                        raw.trim().removeSuffix("%").toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
                            if (raw.contains('%') || (key == "prob" && it > 1)) it / 100 else it
                        }
                    }
                    fun image(vararg keys: String): String = keys.firstNotNullOfOrNull {
                        value.optString(it).takeIf { url -> url.startsWith("https://") }
                    }.orEmpty()
                    Candidate(text, score, value.optString("match_type"), value.optString("component"),
                        listOf("author", "work", "page").map { value.optString(it) }.filter { it.isNotBlank() }.joinToString(" · "),
                        value.optString("codepoint"), image("glyph_image", "glyph_image_url", "image_url", "image"), image("unicode_glyph_image"))
                }
                else -> null
            }
        }.sortedWith(compareBy<Candidate> { when(it.matchType) { "whole" -> 0; "component" -> 1; else -> 2 } }
            .thenByDescending { it.score ?: Double.NEGATIVE_INFINITY }).take(20)
    }
}
