package com.cyeam.seal.update

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cyeam.seal.BuildConfig
import com.cyeam.seal.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class UpdateStage { IDLE, CHECKING, DOWNLOADING, READY, FAILED }
data class UpdateState(val stage: UpdateStage=UpdateStage.IDLE,val version: String="",val notes: String="",val progress: Int=0,val message: Int=0)
class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val downloads=context.getSystemService(DownloadManager::class.java)
    private val prefs=context.getSharedPreferences("app_updates",Context.MODE_PRIVATE)
    private val mutable=MutableStateFlow(UpdateState())
    val state=mutable.asStateFlow()
    private var running=false
    private val apk get()=File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"updates/app-update.apk")
    init { checkForUpdate() }
    fun checkForUpdate() {
        if(running) return
        running=true
        viewModelScope.launch {
            try {
                var id=prefs.getLong("id",-1)
                if(id>=0 && prefs.getLong("versionCode",0)<=BuildConfig.VERSION_CODE) { clearDownload(); id=-1 }
                if(id<0 || mutable.value.stage==UpdateStage.FAILED) {
                    withContext(Dispatchers.IO) { clearDownload() }
                    mutable.value=UpdateState(UpdateStage.CHECKING,message=R.string.checking_update)
                    val update=AppUpdateClient.check()
                    if(update==null) { mutable.value=UpdateState(message=R.string.up_to_date); return@launch }
                    withContext(Dispatchers.IO) {
                        val directory=checkNotNull(apk.parentFile)
                        check(directory.isDirectory || directory.mkdirs())
                    }
                    id=downloads.enqueue(DownloadManager.Request(Uri.parse(update.downloadUrl))
                        .setTitle(context.getString(R.string.app_name)+" "+update.versionName)
                        .setDescription(context.getString(R.string.downloading_update))
                        .setMimeType("application/vnd.android.package-archive")
                        .setAllowedOverMetered(false).setAllowedOverRoaming(false)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,"updates/app-update.apk"))
                    prefs.edit().putLong("id",id).putLong("versionCode",update.versionCode)
                        .putString("versionName",update.versionName).putString("notes",update.notes).putLong("size",update.sizeBytes).apply()
                }
                val current=UpdateState(UpdateStage.DOWNLOADING,prefs.getString("versionName","")!!,prefs.getString("notes","")!!)
                while(true) {
                    val status=withContext(Dispatchers.IO) {
                        downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
                            check(c.moveToFirst())
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        }
                    }
                    when(status.first) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            withContext(Dispatchers.IO) { verifyApk() }
                            mutable.value=current.copy(stage=UpdateStage.READY,progress=100,message=R.string.update_ready); break
                        }
                        DownloadManager.STATUS_FAILED -> throw UpdateException(R.string.update_error)
                        else -> mutable.value=current.copy(progress=(100.0*status.second/prefs.getLong("size",1)).toInt().coerceIn(0,100),message=if(status.first==DownloadManager.STATUS_PAUSED) R.string.waiting_wifi else R.string.downloading_update)
                    }
                    delay(1000)
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.value=mutable.value.copy(stage=UpdateStage.FAILED,message=if(e is UpdateException) e.messageId else R.string.update_error) }
            finally { running=false }
        }
    }
    @Suppress("DEPRECATION")
    fun verifyApk() {
        check(apk.isFile && apk.length()==prefs.getLong("size",-1))
        val pm=context.packageManager
        val downloaded=checkNotNull(pm.getPackageArchiveInfo(apk.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES))
        val installed=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        check(downloaded.packageName==context.packageName && downloaded.longVersionCode==prefs.getLong("versionCode",-1) && downloaded.longVersionCode>BuildConfig.VERSION_CODE)
        val incoming=downloaded.signingInfo?.apkContentsSigners
        val existing=installed.signingInfo?.apkContentsSigners
        check(!incoming.isNullOrEmpty() && !existing.isNullOrEmpty() && incoming.toSet()==existing.toSet())
    }
    suspend fun installIntent(): Intent = withContext(Dispatchers.IO) {
        check(mutable.value.stage==UpdateStage.READY)
        verifyApk()
        Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",apk),"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private fun clearDownload() {
        val id=prefs.getLong("id",-1)
        if(id>=0) downloads.remove(id)
        apk.delete(); prefs.edit().clear().apply()
    }
}
