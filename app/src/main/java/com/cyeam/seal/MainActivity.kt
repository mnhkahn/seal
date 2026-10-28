package com.cyeam.seal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.load
import com.cyeam.seal.ocr.*
import com.cyeam.seal.update.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private val model: SealViewModel by viewModels()
    private val updates: AppUpdateViewModel by viewModels()
    private lateinit var body: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var sourceButtons: LinearLayout
    private var sessionConsent=false
    private var manualUpdateCheck=false
    private var lastPromptedUpdate: String?=null
    private val imageLoader by lazy { ImageLoader.Builder(this).components { add(SvgDecoder.Factory()) }.build() }
    private val picker=registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { model.load(it) } }
    private val camera=registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if(ok) model.cameraUri?.let { model.load(it) }
    }
    private val unknownSources=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(packageManager.canRequestPackageInstalls() && updates.state.value.stage==UpdateStage.READY) installUpdate()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionConsent=savedInstanceState?.getBoolean("upload_consent") ?: false
        manualUpdateCheck=savedInstanceState?.getBoolean("manual_update_check") ?: false
        lastPromptedUpdate=savedInstanceState?.getString("prompted_update")
        model.cameraUri=(savedInstanceState?.getString("camera_uri")?.let(Uri::parse)) ?: model.cameraUri
        val root=column(0)
        root.setBackgroundColor(Color.rgb(248,244,237))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        val scroll=ScrollView(this).apply { isFillViewport=true }
        body=column(20)
        scroll.addView(body); root.addView(scroll)
        setContentView(root)
        WindowInsetsControllerCompat(window,root).apply { isAppearanceLightStatusBars=true; isAppearanceLightNavigationBars=true }
        val header=row()
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.seal_logo)
            contentDescription=getString(R.string.app_name)+", "+getString(R.string.check_update)
            tooltipText=getString(R.string.check_update)
            isFocusable=true
            setOnClickListener { checkUpdateManually() }
        },LinearLayout.LayoutParams(dp(56),dp(56)))
        val heading=column(0).apply { setPadding(dp(14),0,0,0); addView(label(getString(R.string.app_name),26,true)); addView(label(getString(R.string.subtitle),12)) }
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f)); body.addView(header)
        val nav=row()
        nav.addView(button(R.string.history,false) { history() },weight())
        nav.addView(button(R.string.settings,false) { settings() },weight()); body.addView(nav)
        sourceButtons=row()
        sourceButtons.addView(button(R.string.choose_photo) { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },weight())
        sourceButtons.addView(button(R.string.take_photo,false) { takePhoto() },weight()); body.addView(sourceButtons)
        content=column(0); body.addView(content)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { model.state.collect { render(it) } }
            launch { updates.state.collect { handleUpdate(it) } }
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("upload_consent",sessionConsent)
        outState.putBoolean("manual_update_check",manualUpdateCheck)
        outState.putString("prompted_update",lastPromptedUpdate)
        outState.putString("camera_uri",model.cameraUri?.toString())
        super.onSaveInstanceState(outState)
    }
    private fun takePhoto() {
        try {
            val directory=File(cacheDir,"camera").apply { mkdirs() }
            val file=File(directory,"capture.jpg")
            val uri=FileProvider.getUriForFile(this,"$packageName.fileprovider",file)
            model.cameraUri=uri; camera.launch(uri)
        } catch(_: Exception) { toast(R.string.camera_error) }
    }
    private fun render(s: SealState) {
        content.removeAllViews()
        for(i in 0 until sourceButtons.childCount) sourceButtons.getChildAt(i).isEnabled=!s.busy
        if(s.image==null) {
            val welcome=card()
            welcome.addView(label(getString(R.string.welcome),23,true))
            welcome.addView(label(getString(R.string.intro),16))
            welcome.addView(label(getString(R.string.workflow),15,true))
            welcome.addView(label(getString(R.string.local_notice),13))
            if(s.busy) welcome.addView(ProgressBar(this))
            if(s.message!=0) welcome.addView(label(getString(s.message),14))
            content.addView(welcome); return
        }
        val source=card(); source.addView(label(getString(R.string.source),18,true))
        source.addView(ImageView(this).apply {
            setImageBitmap(s.image); scaleType=ImageView.ScaleType.FIT_CENTER
            contentDescription=getString(R.string.source)
        },LinearLayout.LayoutParams(-1,dp(220)))
        val actions=row()
        actions.addView(button(R.string.rotate,false) { model.rotate() }.apply { isEnabled=!s.busy },weight())
        actions.addView(button(R.string.reset,false) { model.clear() }.apply { isEnabled=!s.busy },weight()); source.addView(actions)
        val processing=row()
        processing.addView(button(if(s.vertical) R.string.vertical else R.string.horizontal,false) { chooseDirection() }.apply { isEnabled=!s.busy },weight())
        processing.addView(button(R.string.ink,false) { chooseInk() }.apply { isEnabled=!s.busy },weight()); source.addView(processing)
        val segmentation=row()
        segmentation.addView(button(R.string.auto_segment,false) { model.segment() }.apply { isEnabled=!s.busy },weight())
        segmentation.addView(button(R.string.add_crop,false) { crop(null) }.apply { isEnabled=!s.busy },weight()); source.addView(segmentation)
        source.addView(label(getString(R.string.single_guide),13)); content.addView(source)
        val count=label(getString(R.string.crop_count,s.glyphs.size),20,true); content.addView(count)
        if(s.glyphs.size>10) content.addView(label(getString(R.string.batch_limit),14).apply { setTextColor(Color.rgb(164,51,37)) })
        if(s.message!=0) content.addView(label(getString(s.message),14))
        if(s.busy) {
            content.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=maxOf(1,s.total); progress=s.completed; isIndeterminate=s.total==0
                contentDescription=getString(R.string.progress,s.completed,s.total)
            })
            if(s.total>0) content.addView(label(getString(R.string.progress,s.completed,s.total),14))
            content.addView(button(R.string.cancel,false) { model.cancel() })
        } else if(s.total>0) content.addView(label(getString(R.string.completed,s.glyphs.count { it.candidates.isNotEmpty() },s.glyphs.count { it.error!=0 }),14))
        content.addView(button(R.string.recognize) { startRecognition() }.apply { isEnabled=!s.busy && s.glyphs.none { it.feedbackPending } && s.glyphs.size in 1..10 })
        val text=s.glyphs.filter { it.decision!=-1 }.mapNotNull { it.candidates.getOrNull(it.selected)?.text }.joinToString("")
        if(text.isNotBlank()) {
            val result=card(); result.addView(label(getString(R.string.result),18,true))
            result.addView(label(text,30).apply { setTextIsSelectable(true) })
            result.addView(button(R.string.copy_result,false) { copy(text) }); result.addView(label(getString(R.string.score_notice),12))
            content.addView(result)
        }
        s.glyphs.forEachIndexed { index,glyph -> renderGlyph(s.image,index,glyph,s.busy) }
    }
    private fun renderGlyph(image: Bitmap,index: Int,g: Glyph,busy: Boolean) {
        val panel=card()
        val top=row(); top.addView(label(getString(R.string.glyph_number,index+1),16,true),weight())
        top.addView(button(R.string.edit,false) { crop(index) }.apply { isEnabled=!busy })
        top.addView(button(R.string.delete,false) { model.delete(index) }.apply { isEnabled=!busy }); panel.addView(top)
        val b=g.box
        panel.addView(ImageView(this).apply {
            setImageBitmap(Bitmap.createBitmap(image,b.x,b.y,b.width,b.height)); scaleType=ImageView.ScaleType.FIT_CENTER
            contentDescription=getString(R.string.glyph_number,index+1)
            setOnClickListener { if(!busy) crop(index) }
        },LinearLayout.LayoutParams(-1,dp(136)))
        val candidate=g.candidates.getOrNull(g.selected)
        if(candidate!=null) {
            val controls=row()
            controls.addView(CandidateDropdown(this,g.candidates,g.selected,imageLoader,::candidateLabel) { rank ->
                model.select(index,rank)
            }.apply {
                isEnabled=!busy && g.decision==0 && !g.feedbackPending
                alpha=if(isEnabled) 1f else .6f
            },LinearLayout.LayoutParams(0,-2,1f))
            controls.addView(MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                icon=androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MainActivity,R.drawable.ic_open_in_new)
                iconTint=android.content.res.ColorStateList.valueOf(Color.rgb(108,117,125))
                iconGravity=MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding=0
                insetTop=0; insetBottom=0
                setPadding(dp(14),0,dp(14),0)
                minWidth=0; minimumWidth=0
                cornerRadius=dp(6)
                contentDescription=getString(R.string.dictionary)
                setOnClickListener { openUrl("https://zi.tools/zi/"+Uri.encode(candidate.text)) }
            },LinearLayout.LayoutParams(dp(52),dp(56)).apply { marginStart=dp(8) })
            panel.addView(controls)
            val feedback=row()
            feedback.addView(button(R.string.accept,false) { model.feedback(index,true) }.apply { isEnabled=!busy && g.decision==0 && !g.feedbackPending },weight())
            feedback.addView(button(R.string.reject,false) { model.feedback(index,false) }.apply { isEnabled=!busy && g.decision==0 && !g.feedbackPending },weight()); panel.addView(feedback)
            if(g.feedbackPending) panel.addView(label(getString(R.string.feedback_saving),13))
            if(g.feedbackError!=0) panel.addView(label(getString(g.feedbackError),13))
            if(g.decision!=0) panel.addView(label(getString(if(g.decision==1) R.string.accepted else R.string.rejected),13))
        }
        if(g.error!=0) panel.addView(label(getString(g.error),14).apply { setTextColor(Color.rgb(164,51,37)) })
        panel.addView(button(R.string.retry,false) { startRecognition(index) }.apply { isEnabled=!busy && model.state.value.glyphs.none { it.feedbackPending } })
        content.addView(panel)
    }
    private fun candidateLabel(c: Candidate): String {
        val match=when(c.matchType) {
            "whole" -> getString(R.string.whole_match)
            "component" -> getString(R.string.component_match)+(if(c.component.isNotBlank()) " · "+c.component else "")
            else -> ""
        }
        val standard=if(c.text=="䝿" || c.text=="貴") "（"+getString(R.string.standard_character,"贵")+"）" else ""
        val score=c.score?.let { String.format(resources.configuration.locales[0],"%.1f%%",it*100) }.orEmpty()
        // As on the website, use the Unicode reference image for rare characters
        // instead of showing a missing-font square alongside it.
        val character=if(c.unicodeImage.isNotBlank()) "" else c.text
        return listOf(character+standard,match,score).filter { it.isNotBlank() }.joinToString(" · ")
    }
    private fun crop(index: Int?) {
        val s=model.state.value; val image=s.image ?: return
        val container=column(16)
        container.addView(label(getString(R.string.crop_hint),14))
        val view=CropView(this,image,index?.let { s.glyphs[it].box })
        container.addView(view,LinearLayout.LayoutParams(-1,dp(350)))
        MaterialAlertDialogBuilder(this).setTitle(if(index==null) R.string.add_crop else R.string.edit)
            .setView(container).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.save) { _,_ -> model.setBox(index,view.box()) }.show()
    }
    private fun startRecognition(index: Int?=null) {
        if(sessionConsent) { model.recognize(index); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.upload_title).setMessage(R.string.upload_notice)
            .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.continue_action) { _,_ -> sessionConsent=true; model.recognize(index) }.show()
    }
    private fun chooseDirection() {
        val s=model.state.value
        MaterialAlertDialogBuilder(this).setTitle(R.string.reading_order).setSingleChoiceItems(arrayOf(getString(R.string.vertical),getString(R.string.horizontal)),if(s.vertical) 0 else 1) { d,n -> d.dismiss(); model.segment(vertical=n==0) }.show()
    }
    private fun chooseInk() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.ink).setSingleChoiceItems(arrayOf(getString(R.string.ink_auto),getString(R.string.ink_dark),getString(R.string.ink_light),getString(R.string.ink_red)),model.state.value.ink.ordinal) { d,n -> d.dismiss(); model.segment(ink=Ink.entries[n]) }.show()
    }
    private fun history() {
        lifecycleScope.launch {
            val entries=withContext(Dispatchers.IO) { runCatching { if(model.historyFile.exists()) JSONArray(model.historyFile.readText()) else JSONArray() }.getOrDefault(JSONArray()) }
            val list=column(16)
            if(entries.length()==0) list.addView(label(getString(R.string.history_empty),16))
            for(i in 0 until entries.length()) {
                val item=entries.getJSONObject(i); val text=item.getString("text")
                list.addView(label(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(item.getLong("time"))),12))
                list.addView(label(text,22).apply { setTextIsSelectable(true); setOnClickListener { copy(text) } })
            }
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.history).setView(ScrollView(this@MainActivity).apply { addView(list) })
                .setNegativeButton(R.string.close,null).setNeutralButton(R.string.clear_history) { _,_ -> lifecycleScope.launch(Dispatchers.IO) { model.historyFile.delete() } }.show()
        }
    }
    private fun settings() {
        val list=column(20)
        list.addView(label("${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",16,true))
        list.addView(button(R.string.language,false) { language() })
        list.addView(SwitchMaterial(this).apply {
            text=getString(R.string.analytics); isChecked=model.analytics.enabled
            setOnCheckedChangeListener { _,enabled -> model.analytics.enabled=enabled }
        })
        list.addView(label(getString(R.string.analytics_notice),13))
        list.addView(button(R.string.check_update,false) { checkUpdateManually() })
        list.addView(label(getString(R.string.update_notice),13))
        list.addView(button(R.string.privacy,false) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.privacy).setMessage(R.string.privacy_details).setPositiveButton(R.string.close,null).show()
        })
        MaterialAlertDialogBuilder(this).setTitle(R.string.settings).setView(list).setPositiveButton(R.string.close,null).show()
    }
    private fun language() {
        val codes=arrayOf("","zh-CN","zh-TW","en","ja","ko","fr","de","es","pt","ru")
        val names=arrayOf(getString(R.string.system_language),"简体中文","繁體中文","English","日本語","한국어","Français","Deutsch","Español","Português","Русский")
        val current=AppCompatDelegate.getApplicationLocales().toLanguageTags()
        MaterialAlertDialogBuilder(this).setTitle(R.string.language).setSingleChoiceItems(names,codes.indexOf(current).coerceAtLeast(0)) { d,i ->
            d.dismiss(); AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(codes[i]))
        }.setNegativeButton(R.string.cancel,null).show()
    }
    private fun checkUpdateManually() {
        val state=updates.state.value
        when(state.stage) {
            UpdateStage.READY -> showReadyUpdate(state)
            UpdateStage.CHECKING -> {
                manualUpdateCheck=true
                toast(R.string.checking_update)
            }
            UpdateStage.DOWNLOADING -> toast(if(state.message!=0) state.message else R.string.downloading_update)
            else -> {
                manualUpdateCheck=true
                toast(R.string.checking_update)
                updates.checkForUpdate()
            }
        }
    }
    private fun handleUpdate(state: UpdateState) {
        // Startup checks remain quiet unless a verified APK is ready to install.
        if(state.stage==UpdateStage.READY) {
            manualUpdateCheck=false
            if(lastPromptedUpdate!=state.version) showReadyUpdate(state)
        } else if(manualUpdateCheck && state.stage!=UpdateStage.CHECKING) {
            manualUpdateCheck=false
            if(state.message!=0) toast(state.message)
        }
    }
    private fun showReadyUpdate(state: UpdateState) {
        lastPromptedUpdate=state.version
        MaterialAlertDialogBuilder(this).setTitle(R.string.app_update)
            .setMessage(listOf(state.version,state.notes,getString(R.string.update_ready)).filter { it.isNotBlank() }.joinToString("\n\n"))
            .setNegativeButton(R.string.close,null)
            .setPositiveButton(R.string.install_update) { _,_ -> installUpdate() }.show()
    }
    private fun installUpdate() {
        if(!packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.install_update).setMessage(R.string.install_permission)
                .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.continue_action) { _,_ ->
                    runCatching { unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName"))) }.onFailure { toast(R.string.update_error) }
                }.show(); return
        }
        lifecycleScope.launch {
            try { startActivity(updates.installIntent()); model.analytics.event("seal_update_install") }
            catch(_: Exception) { toast(R.string.update_error) }
        }
    }
    private fun openUrl(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }.onFailure { toast(R.string.browser_error) } }
    private fun copy(value: String) { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.result),value)); toast(R.string.copied); model.analytics.event("seal_result_copy") }
    private fun toast(id: Int) = Toast.makeText(this,id,Toast.LENGTH_SHORT).show()
    private fun dp(n: Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun column(padding: Int)=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(padding),dp(padding),dp(padding),dp(padding)) }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun weight()=LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(4) }
    private fun card()=column(16).apply {
        background=GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=dp(20).toFloat(); setStroke(dp(1),Color.rgb(230,223,212)) }
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12); bottomMargin=dp(8) }
    }
    private fun label(value: String,size: Int,bold: Boolean=false)=TextView(this).apply {
        text=value; textSize=size.toFloat(); setTextColor(Color.rgb(49,43,37)); setPadding(0,dp(6),0,dp(6))
        if(bold) setTypeface(typeface,Typeface.BOLD)
    }
    private fun button(id: Int,primary: Boolean=true,action: ()->Unit): MaterialButton {
        val style=if(primary) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle
        return MaterialButton(this,null,style).apply { setText(id); isAllCaps=false; minHeight=dp(48); cornerRadius=dp(12); setOnClickListener { action() } }
    }
}
