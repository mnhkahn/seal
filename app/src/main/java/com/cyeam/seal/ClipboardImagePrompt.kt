package com.cyeam.seal

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Checks image clips only while the app has focus; confirmation never uploads an image. */
class ClipboardImagePrompt(
    private val activity: AppCompatActivity,
    private val canImport: () -> Boolean,
    private val hasImage: () -> Boolean,
    private val onImport: (Uri) -> Unit
) : DefaultLifecycleObserver {
    private val clipboard = activity.getSystemService(ClipboardManager::class.java)
    private val preferences = activity.getSharedPreferences("clipboard_images", Context.MODE_PRIVATE)
    private var previewJob: Job? = null
    private var dialog: AlertDialog? = null
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        activity.window.decorView.post { check() }
    }

    init { activity.lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) { clipboard.addPrimaryClipChangedListener(listener) }
    override fun onResume(owner: LifecycleOwner) { activity.window.decorView.post { check() } }
    override fun onStop(owner: LifecycleOwner) {
        clipboard.removePrimaryClipChangedListener(listener)
        previewJob?.cancel()
        dialog?.dismiss()
        dialog = null
    }

    fun check() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            || !activity.hasWindowFocus() || !canImport() || dialog != null || previewJob?.isActive == true) return
        try {
            val description = clipboard.primaryClipDescription ?: return
            // Avoid retrieving clipboard text. Image providers may advertise a URI stream instead.
            if (!description.hasMimeType("image/*") && !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) return
            val clip = clipboard.primaryClip ?: return
            val uri = (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri } ?: return
            if (uri.scheme !in setOf("content", "file", "android.resource")) return
            val timestamp = clip.description.timestamp
            // Keep only an opaque identifier, never the clipboard URI or image, in preferences.
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest("$timestamp\n$uri".toByteArray()).joinToString("") { "%02x".format(it) }
            if (preferences.getString("offered", null) == fingerprint) return
            previewJob = activity.lifecycleScope.launch {
                try {
                    val preview = withContext(Dispatchers.IO) {
                        val mime = activity.contentResolver.getType(uri)
                        if (mime?.startsWith("image/") != true && !clip.description.hasMimeType("image/*")) return@withContext null
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, uri)) { decoder, info, _ ->
                            val scale = 600.0 / maxOf(info.size.width, info.size.height)
                            if (scale < 1) decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } ?: return@launch
                    if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        || !activity.hasWindowFocus() || !canImport()) return@launch
                    if (clipboard.primaryClipDescription?.timestamp != timestamp) {
                        activity.window.decorView.post { check() }
                        return@launch
                    }
                    val padding = dp(16)
                    val image = ImageView(activity).apply {
                        setImageBitmap(preview)
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
                        .setPositiveButton(R.string.clipboard_use_image) { _, _ -> onImport(uri) }
                        .create()
                    dialog = prompt
                    prompt.setOnDismissListener {
                        dialog = null
                        activity.window.decorView.post { check() }
                    }
                    // Mark offered clips before showing so focus changes cannot reopen the same prompt.
                    preferences.edit().putString("offered", fingerprint).apply()
                    prompt.show()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Expired URI grants or inaccessible images are ignored. */ }
            }
        } catch (_: Exception) { /* Clipboard may be unavailable when window focus changes. */ }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
}
