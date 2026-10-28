package com.cyeam.seal

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Checks image clips only while the app has focus; confirmation never uploads an image. */
class ClipboardImagePrompt(
    private val activity: AppCompatActivity,
    private val canImport: () -> Boolean,
    private val hasImage: () -> Boolean,
    private val onImport: (Bitmap) -> Unit
) : DefaultLifecycleObserver {
    private val clipboard = activity.getSystemService(ClipboardManager::class.java)
    private val preferences = activity.getSharedPreferences("clipboard_images", Context.MODE_PRIVATE)
    private var previewJob: Job? = null
    private var dialog: AlertDialog? = null
    private var offeredThisVisit: String? = null
    private val retry = Runnable { check() }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) scheduleCheck()
    }

    private fun scheduleCheck() {
        activity.window.decorView.removeCallbacks(retry)
        activity.window.decorView.post { check() }
        // Cross-device clipboard providers may finish publishing after resume/focus.
        activity.window.decorView.postDelayed(retry, 800)
    }
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        activity.window.decorView.post { check() }
    }

    init { activity.lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) {
        offeredThisVisit = null
        clipboard.addPrimaryClipChangedListener(listener)
    }
    override fun onResume(owner: LifecycleOwner) { scheduleCheck() }
    override fun onStop(owner: LifecycleOwner) {
        clipboard.removePrimaryClipChangedListener(listener)
        activity.window.decorView.removeCallbacks(retry)
        previewJob?.cancel()
        previewJob = null
        dialog?.dismiss()
        dialog = null
    }

    fun check() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            || !activity.hasWindowFocus() || !canImport() || dialog != null || previewJob?.isActive == true) return
        try {
            val clip = clipboard.primaryClip ?: return
            // Cross-device clips can advertise generic file MIME types or put the URI
            // in an Intent/text item. Inspect only local URI references; never coerce
            // ordinary text or fetch web links.
            val uris = (0 until clip.itemCount).mapNotNull { index ->
                val item = clip.getItemAt(index)
                val uri = item.uri ?: item.intent?.data ?: item.text?.toString()
                    ?.takeIf { it.length <= 4096 && (it.startsWith("content://") || it.startsWith("file://")) }
                    ?.let(Uri::parse)
                uri?.takeIf { it.scheme in setOf("content", "file", "android.resource") }
            }
            if (uris.isEmpty()) return
            val timestamp = clip.description.timestamp
            // Keep only an opaque identifier, never the clipboard URI or image, in preferences.
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest("$timestamp\n${uris.joinToString("\n")}".toByteArray()).joinToString("") { "%02x".format(it) }
            if (offeredThisVisit == fingerprint || preferences.getString("imported", null) == fingerprint) return
            previewJob = activity.lifecycleScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        // Snapshot once: lazy providers may expose a non-seekable pipe
                        // and may not support reopening the same remote image.
                        var result: Bitmap? = null
                        for (uri in uris) {
                            ensureActive()
                            try {
                                val bytes = activity.contentResolver.openInputStream(uri)?.use { input ->
                                    ByteArrayOutputStream().use { output ->
                                        val buffer = ByteArray(16 * 1024)
                                        while (true) {
                                            ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            check(output.size().toLong() + count <= 32L * 1024 * 1024)
                                            output.write(buffer, 0, count)
                                        }
                                        output.toByteArray()
                                    }
                                } ?: continue
                                result = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                                    val scale = 1800.0 / maxOf(info.size.width, info.size.height)
                                    if (scale < 1) decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                }
                                break
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Try the next local image item. */ }
                        }
                        result
                    } ?: return@launch
                    if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        || !activity.hasWindowFocus() || !canImport()) return@launch
                    if (clipboard.primaryClipDescription?.timestamp != timestamp) {
                        scheduleCheck()
                        return@launch
                    }
                    val padding = dp(16)
                    val image = ImageView(activity).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = activity.getString(R.string.clipboard_image_title)
                    }
                    val container = LinearLayout(activity).apply {
                        setPadding(padding, padding, padding, 0)
                        addView(image, LinearLayout.LayoutParams(-1, dp(220)))
                    }
                    val message = activity.getString(R.string.clipboard_image_message) +
                        if (hasImage()) "\n" + activity.getString(R.string.clipboard_replace_image) else ""
                    val prompt = MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.clipboard_image_title).setMessage(message).setView(container)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.clipboard_use_image) { _, _ ->
                            preferences.edit().putString("imported", fingerprint).apply()
                            onImport(bitmap)
                        }
                        .create()
                    dialog = prompt
                    prompt.setOnDismissListener {
                        dialog = null
                        activity.window.decorView.post { check() }
                    }
                    // Avoid repeated prompts in this visit, but allow a declined image
                    // again after switching away and returning to the app.
                    offeredThisVisit = fingerprint
                    prompt.show()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Expired URI grants or inaccessible images are ignored. */ }
            }
        } catch (_: Exception) { /* Clipboard may be unavailable when window focus changes. */ }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
}
