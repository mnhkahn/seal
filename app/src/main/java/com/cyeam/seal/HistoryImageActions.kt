package com.cyeam.seal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Export the local original, independently of thumbnail size or recognition services. */
class HistoryImageActions(private val activity: AppCompatActivity) {
    private val exportDirectory get() = File(activity.cacheDir, "history-export").apply { mkdirs() }
    // A fixed staging file survives activity recreation while the system file picker is open.
    private val documentSource get() = File(exportDirectory, "document.png")
    private val createDocument = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) runAction(R.string.image_saved) {
            try { write(documentSource, uri) }
            catch (error: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(activity.contentResolver, uri) }
                throw error
            } finally { documentSource.delete() }
        } else documentSource.delete()
    }

    fun show(file: File) {
        val options = intArrayOf(R.string.download_image, R.string.save_to_photos, R.string.copy_image)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.history_image_actions)
            .setItems(options.map(activity::getString).toTypedArray()) { _, index ->
                when (index) {
                    0 -> save(file, album = false)
                    1 -> save(file, album = true)
                    2 -> copy(file)
                }
            }.show()
    }

    private fun save(file: File, album: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { file.copyTo(documentSource, overwrite = true) }
                    createDocument.launch("Seal-${UUID.randomUUID()}.png")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { toast(R.string.image_action_error) }
            }
            return
        }
        runAction(R.string.image_saved) {
            val resolver = activity.contentResolver
            val collection = if (album) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Seal-${UUID.randomUUID()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (album) "${Environment.DIRECTORY_PICTURES}/Seal" else "${Environment.DIRECTORY_DOWNLOADS}/Seal")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(collection, values))
            try {
                write(file, uri)
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }
    }

    private fun copy(file: File) {
        activity.lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val snapshot = File(exportDirectory, "${UUID.randomUUID()}.png")
                    file.copyTo(snapshot)
                    FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", snapshot)
                }
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newUri(activity.contentResolver, activity.getString(R.string.copy_image), uri))
                toast(R.string.copied)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { toast(R.string.image_action_error) }
        }
    }

    private fun write(file: File, uri: Uri) {
        checkNotNull(activity.contentResolver.openOutputStream(uri, "w")).use { output ->
            file.inputStream().use { it.copyTo(output) }
        }
    }

    private fun runAction(success: Int, block: () -> Unit) {
        activity.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { block() }; toast(success) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { toast(R.string.image_action_error) }
        }
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
