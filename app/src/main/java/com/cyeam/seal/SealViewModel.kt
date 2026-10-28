package com.cyeam.seal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cyeam.seal.ocr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

data class Glyph(
    val box: Box, val candidates: List<Candidate> = emptyList(), val selected: Int = 0,
    val error: Int = 0, val decision: Int = 0
)
data class SealState(
    val image: Bitmap? = null, val glyphs: List<Glyph> = emptyList(), val busy: Boolean = false,
    val completed: Int = 0, val total: Int = 0, val message: Int = 0,
    val vertical: Boolean = true, val ink: Ink = Ink.AUTO
)
class SealViewModel(app: Application) : AndroidViewModel(app) {
    val analytics = Analytics(app)
    private val mutable = MutableStateFlow(SealState())
    val state = mutable.asStateFlow()
    var cameraUri: Uri? = null
    private var job: Job? = null
    val historyFile = File(app.filesDir, "history.json")

    fun load(uri: Uri) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = 0)
        job = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val ratio = 1800.0 / maxOf(info.size.width, info.size.height)
                        if (ratio < 1) decoder.setTargetSize((info.size.width * ratio).roundToInt().coerceAtLeast(1), (info.size.height * ratio).roundToInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }
                mutable.value = SealState(image = bitmap, vertical = mutable.value.vertical, ink = mutable.value.ink)
                analytics.event("seal_image_import")
                segment()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, message = R.string.image_error) }
        }
    }
    fun segment(vertical: Boolean = mutable.value.vertical, ink: Ink = mutable.value.ink) {
        val current = mutable.value
        val image = current.image ?: return
        if (current.busy) return
        mutable.value = current.copy(busy = true, vertical = vertical, ink = ink, message = 0, completed = 0, total = 0)
        job = viewModelScope.launch {
            try {
                val boxes = withContext(Dispatchers.Default) {
                    val pixels = IntArray(image.width * image.height)
                    image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                    Segmentation.segment(pixels,image.width,image.height,vertical,ink)
                }
                mutable.value = mutable.value.copy(busy = false, glyphs = boxes.map { Glyph(it) }, message = if (boxes.isEmpty()) R.string.no_segments else 0)
                analytics.event("seal_segmentation", "glyph_count" to boxes.size)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, message = R.string.image_error) }
        }
    }
    fun rotate() {
        val s = mutable.value; val image = s.image ?: return
        if(s.busy) return
        mutable.value = s.copy(image = Bitmap.createBitmap(image,0,0,image.width,image.height,Matrix().apply { postRotate(90f) },true), glyphs = emptyList())
        segment()
    }
    fun setBox(index: Int?, box: Box) {
        if(mutable.value.busy) return
        val list = mutable.value.glyphs.toMutableList()
        if(index == null) list += Glyph(box) else list[index] = Glyph(box)
        mutable.value = mutable.value.copy(glyphs = list, message = 0, completed = 0, total = 0)
    }
    fun delete(index: Int) { if(!mutable.value.busy) mutable.value = mutable.value.copy(glyphs = mutable.value.glyphs.filterIndexed { i,_ -> i != index }, completed = 0, total = 0) }
    fun clear() { if(!mutable.value.busy) mutable.value = SealState(vertical = mutable.value.vertical, ink = mutable.value.ink) }
    fun cancel() { job?.cancel(); mutable.value = mutable.value.copy(busy = false, message = R.string.cancelled) }
    fun select(index: Int, rank: Int) {
        val list = mutable.value.glyphs.toMutableList()
        if (list[index].decision != 0) return
        list[index] = list[index].copy(selected = rank)
        mutable.value = mutable.value.copy(glyphs = list)
        analytics.event("seal_candidate_select", "candidate_rank" to rank + 1, "candidate_count" to list[index].candidates.size)
    }
    fun feedback(index: Int, accepted: Boolean) {
        val list = mutable.value.glyphs.toMutableList(); val glyph = list[index]
        if(glyph.decision != 0 || glyph.candidates.isEmpty()) return
        list[index] = glyph.copy(decision = if(accepted) 1 else -1)
        mutable.value = mutable.value.copy(glyphs = list)
        analytics.event(if(accepted) "seal_result_accept" else "seal_result_reject", "candidate_rank" to if(accepted) glyph.selected + 1 else 0, "candidate_count" to glyph.candidates.size)
    }
    fun recognize(only: Int? = null) {
        val snapshot = mutable.value; val image = snapshot.image ?: return
        val indices = if(only != null) listOf(only) else snapshot.glyphs.indices.toList()
        if (snapshot.busy || indices.isEmpty() || indices.size > 10) return
        mutable.value = snapshot.copy(busy = true, completed = 0, total = indices.size, message = 0)
        job = viewModelScope.launch {
            var success = 0
            analytics.event("seal_recognition_start", "glyph_count" to indices.size)
            try {
                for ((n,index) in indices.withIndex()) {
                    val glyph = mutable.value.glyphs[index]
                    val replacement = try {
                        val bytes = withContext(Dispatchers.Default) {
                            val b = glyph.box
                            val crop = Bitmap.createBitmap(image,b.x,b.y,b.width,b.height)
                            ByteArrayOutputStream().use { stream -> crop.compress(Bitmap.CompressFormat.PNG,100,stream); stream.toByteArray() }
                        }
                        val candidates = OcrClient.recognize(bytes)
                        if(candidates.isEmpty()) glyph.copy(error = R.string.no_candidates, candidates = emptyList(), decision = 0)
                        else {
                            success++
                            saveHistory(candidates)
                            analytics.event("seal_result_view", "candidate_count" to candidates.size)
                            glyph.copy(candidates = candidates, selected = 0, error = 0, decision = 0)
                        }
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) {
                        val error = when {
                            e is OcrException && e.status == 429 -> R.string.rate_limit
                            e is OcrException && e.status == 400 -> R.string.invalid_crop
                            else -> R.string.network_error
                        }
                        glyph.copy(error = error, candidates = emptyList(), decision = 0)
                    }
                    val list = mutable.value.glyphs.toMutableList(); list[index] = replacement
                    mutable.value = mutable.value.copy(glyphs = list, completed = n + 1)
                }
                analytics.event("seal_recognition_complete", "glyph_count" to indices.size, "success_count" to success, "failure_count" to indices.size - success)
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private suspend fun saveHistory(candidates: List<Candidate>) = withContext(Dispatchers.IO) {
        runCatching {
            val old = if(historyFile.exists()) JSONArray(historyFile.readText()) else JSONArray()
            val fresh = JSONArray().put(JSONObject().put("time",System.currentTimeMillis()).put("text",candidates.joinToString(" · ") { it.text }))
            for(i in 0 until minOf(old.length(),49)) fresh.put(old.getJSONObject(i))
            val temp = File(historyFile.parentFile,"history.tmp")
            temp.writeText(fresh.toString()); check(temp.renameTo(historyFile))
        }
    }
}
