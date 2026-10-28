package com.cyeam.seal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cyeam.seal.ocr.*
import com.cyeam.seal.editor.*
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
    val error: Int = 0, val decision: Int = 0, val feedbackToken: String = "",
    val feedbackPending: Boolean = false, val feedbackError: Int = 0
)
enum class InputKind { PHOTO, HANDWRITING }
data class SealState(
    val image: Bitmap? = null, val glyphs: List<Glyph> = emptyList(), val busy: Boolean = false,
    val completed: Int = 0, val total: Int = 0, val message: Int = 0,
    val vertical: Boolean = true, val ink: Ink = Ink.AUTO,
    val inputKind: InputKind = InputKind.PHOTO, val editing: Boolean = true,
    val selectedBox: Int? = null, val addingBox: Boolean = false, val canUndoBoxes: Boolean = false
)
class SealViewModel(app: Application) : AndroidViewModel(app) {
    val analytics = Analytics(app)
    private val mutable = MutableStateFlow(SealState())
    val state = mutable.asStateFlow()
    var cameraUri: Uri? = null
    val boxEditor = BoxEditor()
    val boxViewport = BoxViewport()
    val handwriting = StrokeHistory()
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
                boxEditor.reset(emptyList()); boxViewport.reset()
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
        if (current.busy || current.inputKind!=InputKind.PHOTO) return
        mutable.value = current.copy(busy = true, vertical = vertical, ink = ink, message = 0, completed = 0, total = 0, editing = true, addingBox = false)
        job = viewModelScope.launch {
            try {
                val boxes = withContext(Dispatchers.Default) {
                    val pixels = IntArray(image.width * image.height)
                    image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                    Segmentation.segment(pixels,image.width,image.height,vertical,ink)
                }
                boxEditor.reset(boxes); boxViewport.reset()
                mutable.value = mutable.value.copy(busy = false, glyphs = boxes.map { Glyph(it) }, selectedBox = boxEditor.selected, canUndoBoxes = false, message = if (boxes.isEmpty()) R.string.no_segments else 0)
                analytics.event("seal_segmentation", "glyph_count" to boxes.size)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, message = R.string.image_error) }
        }
    }
    private fun canEditBoxes() = !mutable.value.busy && mutable.value.editing && mutable.value.inputKind==InputKind.PHOTO && mutable.value.glyphs.none { it.feedbackPending }
    fun editorChanged() {
        if(!canEditBoxes()) return
        val s=mutable.value
        val same=boxEditor.boxes==s.glyphs.map { it.box }
        mutable.value=s.copy(glyphs=if(same) s.glyphs else boxEditor.boxes.map { Glyph(it) },
            selectedBox=boxEditor.selected, addingBox=false, canUndoBoxes=boxEditor.canUndo,
            completed=if(same) s.completed else 0,total=if(same) s.total else 0,message=0)
    }
    fun selectBox(index: Int) { if(canEditBoxes()) { boxEditor.select(index);editorChanged() } }
    fun toggleAddBox() { if(canEditBoxes()) mutable.value=mutable.value.copy(addingBox=!mutable.value.addingBox) }
    fun splitBox(horizontal: Boolean) { if(canEditBoxes()) { boxEditor.split(horizontal);editorChanged() } }
    fun deleteBox() { if(canEditBoxes()) { boxEditor.delete();editorChanged() } }
    fun undoBoxes() { if(canEditBoxes()) { boxEditor.undo();editorChanged() } }
    fun editOriginal() {
        val s=mutable.value
        if(s.busy || s.glyphs.any { it.feedbackPending } || s.inputKind!=InputKind.PHOTO) return
        boxEditor.reset(s.glyphs.map { it.box })
        mutable.value=s.copy(editing=true,selectedBox=boxEditor.selected,addingBox=false,canUndoBoxes=false)
    }
    fun confirmAndRecognize() {
        if(!canEditBoxes() || mutable.value.glyphs.isEmpty()) return
        mutable.value=mutable.value.copy(editing=false,addingBox=false)
        recognize()
    }
    fun recognizeHandwriting(bitmap: Bitmap) {
        val s=mutable.value
        if(s.busy || s.glyphs.any { it.feedbackPending } || handwriting.count==0) return
        // One whole canvas, one request. Never call segmentation for handwriting.
        mutable.value=SealState(image=bitmap,glyphs=listOf(Glyph(Box(0,0,bitmap.width,bitmap.height))),
            inputKind=InputKind.HANDWRITING,editing=false,vertical=s.vertical,ink=s.ink)
        recognize()
    }
    fun clear() {
        if(mutable.value.busy) return
        boxEditor.reset(emptyList());boxViewport.reset()
        mutable.value=SealState(vertical=mutable.value.vertical,ink=mutable.value.ink)
    }
    fun cancel() { job?.cancel(); mutable.value=mutable.value.copy(busy=false,message=R.string.cancelled) }
    fun select(index: Int, rank: Int) {
        val list = mutable.value.glyphs.toMutableList()
        if (list[index].decision != 0 || list[index].feedbackPending || mutable.value.busy) return
        list[index] = list[index].copy(selected = rank)
        mutable.value = mutable.value.copy(glyphs = list)
        analytics.event("seal_candidate_select", "candidate_rank" to rank + 1, "candidate_count" to list[index].candidates.size)
    }
    fun feedback(index: Int, accepted: Boolean) {
        val snapshot = mutable.value
        val glyph = snapshot.glyphs.getOrNull(index) ?: return
        if (snapshot.busy || glyph.decision != 0 || glyph.feedbackPending || glyph.candidates.isEmpty()) return
        if (glyph.feedbackToken.isBlank()) {
            val list = snapshot.glyphs.toMutableList()
            list[index] = glyph.copy(feedbackError = R.string.feedback_expired)
            mutable.value = snapshot.copy(glyphs = list)
            return
        }
        fun update(transform: (Glyph) -> Glyph) {
            // Edits/deletions may move or replace the glyph during the request.
            // Never attach the response to a different recognition result.
            val current = mutable.value
            mutable.value = current.copy(glyphs = current.glyphs.map {
                if (it.feedbackToken == glyph.feedbackToken) transform(it) else it
            })
        }
        update { it.copy(feedbackPending = true, feedbackError = 0) }
        viewModelScope.launch {
            try {
                OcrClient.feedback(glyph.feedbackToken, accepted, glyph.selected + 1, glyph.candidates[glyph.selected].text)
                update { it.copy(decision = if (accepted) 1 else -1) }
                analytics.event(if (accepted) "seal_result_accept" else "seal_result_reject", "candidate_rank" to if (accepted) glyph.selected + 1 else 0, "candidate_count" to glyph.candidates.size)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                update { it.copy(feedbackError = if (e is OcrException && e.status == 400) R.string.feedback_expired else R.string.feedback_error) }
            } finally { update { it.copy(feedbackPending = false) } }
        }
    }
    fun recognize(only: Int? = null) {
        val snapshot = mutable.value; val image = snapshot.image ?: return
        val indices = if(only != null) listOf(only) else snapshot.glyphs.indices.toList()
        if (snapshot.busy || snapshot.glyphs.any { it.feedbackPending } || indices.isEmpty() || indices.any { it !in snapshot.glyphs.indices }) return
        mutable.value = snapshot.copy(busy = true, completed = 0, total = indices.size, message = 0, editing = false)
        job = viewModelScope.launch {
            var success = 0
            analytics.event("seal_recognition_start", "glyph_count" to indices.size)
            try {
                for ((n,index) in indices.withIndex()) {
                    val glyph = mutable.value.glyphs[index].copy(feedbackToken = "", feedbackError = 0)
                    val replacement = try {
                        val bytes = withContext(Dispatchers.Default) {
                            val b = glyph.box
                            val crop = Bitmap.createBitmap(image,b.x,b.y,b.width,b.height)
                            ByteArrayOutputStream().use { stream -> crop.compress(Bitmap.CompressFormat.PNG,100,stream); stream.toByteArray() }
                        }
                        val result = OcrClient.recognize(bytes)
                        val candidates = result.candidates
                        if(candidates.isEmpty()) glyph.copy(error = R.string.no_candidates, candidates = emptyList(), decision = 0)
                        else {
                            success++
                            saveHistory(candidates)
                            analytics.event("seal_result_view", "candidate_count" to candidates.size)
                            glyph.copy(candidates = candidates, selected = 0, error = 0, decision = 0, feedbackToken = result.feedbackToken)
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
