package com.cyeam.seal.history

import com.cyeam.seal.ocr.Candidate
import com.cyeam.seal.ocr.OcrClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class HistoryRecord(
    val id: String, val time: Long, val text: String,
    val image: File?, val candidates: List<Candidate>
)

/** App-private images and an atomic index. Image retention is bounded independently of record count. */
class HistoryStore(private val directory: File, private val limit: Int = 50,
                   private val maxImageBytes: Long = 32L * 1024 * 1024) {
    private val index = File(directory, "history.json")
    private val images = File(directory, "history-images")

    @Synchronized fun list(): List<HistoryRecord> = read().map { item ->
        val image = imageFile(item.optString("id"))?.takeIf { it.isFile }
        HistoryRecord(item.getString("id"), item.getLong("time"), item.optString("text"), image,
            item.optJSONArray("candidates")?.let { OcrClient.parse(it.toString()) }.orEmpty())
    }

    @Synchronized fun add(png: ByteArray, candidates: List<Candidate>) {
        require(png.isNotEmpty() && png.size <= maxImageBytes && candidates.isNotEmpty())
        val old = read()
        check(images.isDirectory || images.mkdirs())
        val id = UUID.randomUUID().toString()
        val image = imageFile(id)!!
        try {
            FileOutputStream(image).use { it.write(png); it.fd.sync() }
            val encoded = JSONArray()
            for (candidate in candidates.take(20)) {
                val value = JSONObject().put("char", candidate.text).put("match_type", candidate.matchType)
                    .put("component", candidate.component).put("work", candidate.work)
                    .put("codepoint", candidate.codepoint).put("glyph_image", candidate.glyphImage)
                    .put("unicode_glyph_image", candidate.unicodeImage).put("variants", JSONArray(candidate.variants))
                candidate.score?.let { value.put("score", it) }
                encoded.put(value)
            }
            val fresh = JSONObject().put("id", id).put("time", System.currentTimeMillis())
                .put("text", candidates.joinToString(" · ") { it.text }).put("candidates", encoded)
            var bytes = 0L
            val retained = (listOf(fresh) + old).take(limit).takeWhile {
                bytes += imageFile(it.optString("id"))?.length() ?: 0L
                bytes <= maxImageBytes
            }
            write(retained)
        } catch (error: Exception) {
            image.delete()
            throw error
        }
    }

    @Synchronized fun delete(id: String) { write(read().filter { it.optString("id") != id }) }

    @Synchronized fun clear() {
        // Clear a damaged index too, without first trying to parse it.
        write(emptyList())
    }

    private fun read(): List<JSONObject> {
        if (!index.exists()) return emptyList()
        val array = JSONArray(index.readText())
        return (0 until array.length()).map { position ->
            array.getJSONObject(position).also {
                // Preserve legacy text-only records; their old images cannot be recovered.
                if (!it.has("id")) it.put("id", "legacy-$position-${it.getLong("time")}")
            }
        }
    }

    private fun imageFile(id: String): File? =
        if (id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) File(images, "$id.png") else null

    private fun write(records: List<JSONObject>) {
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "history.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(JSONArray(records).toString().toByteArray(Charsets.UTF_8)); it.fd.sync()
            }
            check(temporary.renameTo(index))
        } finally { temporary.delete() }
        val keep = records.mapNotNull { imageFile(it.optString("id"))?.name }.toSet()
        runCatching { images.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() } }
    }
}
