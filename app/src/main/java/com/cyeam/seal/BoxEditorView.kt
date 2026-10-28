package com.cyeam.seal

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.cyeam.seal.editor.BoxEditor
import com.cyeam.seal.editor.BoxViewport
import com.cyeam.seal.ocr.Box
import kotlin.math.*

/** All boxes stay on the original. Geometry is committed only when a gesture ends. */
class BoxEditorView(
    context: Context, private val image: Bitmap, private val editor: BoxEditor,
    private val viewport: BoxViewport, private val adding: Boolean, private val changed: () -> Unit
) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val target=RectF()
    private var base=1f; private var ox=0f; private var oy=0f
    private var working: List<Box>?=null
    private var start=PointF(); private var startBox: Box?=null
    private var mode=""; private var handle=""; private var index: Int?=null
    private var panStartX=0f; private var panStartY=0f
    private var downX=0f; private var downY=0f
    private var multitouch=false
    private val scaleDetector=ScaleGestureDetector(context,object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old=viewport.zoom; val next=(old*detector.scaleFactor).coerceIn(1f,5f)
            viewport.panX=detector.focusX-ox-(detector.focusX-ox-viewport.panX)*next/old
            viewport.panY=detector.focusY-oy-(detector.focusY-oy-viewport.panY)*next/old
            viewport.zoom=next; clampPan(); invalidate(); return true
        }
    })
    init { contentDescription=context.getString(R.string.box_editor_hint); isFocusable=true }
    private val scale get()=base*viewport.zoom
    private fun geometry() { base=min(width.toFloat()/image.width,height.toFloat()/image.height); ox=(width-image.width*base)/2; oy=(height-image.height*base)/2; clampPan() }
    private fun clampPan() {
        val extraX=image.width*base*(viewport.zoom-1);val extraY=image.height*base*(viewport.zoom-1)
        viewport.panX=viewport.panX.coerceIn(-extraX,0f);viewport.panY=viewport.panY.coerceIn(-extraY,0f)
    }
    private fun rect(b: Box)=RectF(ox+viewport.panX+b.x*scale,oy+viewport.panY+b.y*scale,ox+viewport.panX+(b.x+b.width)*scale,oy+viewport.panY+(b.y+b.height)*scale)
    private fun point(x: Float,y: Float)=PointF(((x-ox-viewport.panX)/scale).coerceIn(0f,image.width.toFloat()),((y-oy-viewport.panY)/scale).coerceIn(0f,image.height.toFloat()))
    private fun handles(r: RectF)=listOf("nw" to PointF(r.left,r.top),"n" to PointF(r.centerX(),r.top),"ne" to PointF(r.right,r.top),"e" to PointF(r.right,r.centerY()),"se" to PointF(r.right,r.bottom),"s" to PointF(r.centerX(),r.bottom),"sw" to PointF(r.left,r.bottom),"w" to PointF(r.left,r.centerY()))
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);geometry();canvas.drawColor(0xFFF0EBE2.toInt())
        target.set(ox+viewport.panX,oy+viewport.panY,ox+viewport.panX+image.width*scale,oy+viewport.panY+image.height*scale)
        canvas.drawBitmap(image,null,target,paint)
        val boxes=working ?: editor.boxes
        boxes.forEachIndexed { i,b ->
            val r=rect(b);val selected=i==editor.selected
            if(selected) {paint.style=Paint.Style.FILL;paint.color=0x182463D6;canvas.drawRect(r,paint)}
            paint.style=Paint.Style.STROKE;paint.color=0xFF2463D6.toInt();paint.strokeWidth=dp(if(selected) 3 else 2);canvas.drawRect(r,paint)
            paint.style=Paint.Style.FILL;paint.textSize=dp(13);val text="${i+1}";val w=paint.measureText(text)+dp(8)
            canvas.drawRect(r.left,r.top,r.left+w,r.top+dp(21),paint);paint.color=Color.WHITE;canvas.drawText(text,r.left+dp(4),r.top+dp(15),paint)
            if(selected) for((_,p) in handles(r)) {paint.color=Color.WHITE;canvas.drawRect(p.x-dp(5),p.y-dp(5),p.x+dp(5),p.y+dp(5),paint);paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(2);paint.color=0xFF2463D6.toInt();canvas.drawRect(p.x-dp(5),p.y-dp(5),p.x+dp(5),p.y+dp(5),paint);paint.style=Paint.Style.FILL}
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!isEnabled) return false
        geometry()
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN) { multitouch=true;working=null;mode="" }
        scaleDetector.onTouchEvent(event)
        if(multitouch) {
            if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL) {multitouch=false;parent?.requestDisallowInterceptTouchEvent(false);changed()}
            invalidate();return true
        }
        val p=point(event.x,event.y)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true);start=p;downX=event.x;downY=event.y;panStartX=viewport.panX;panStartY=viewport.panY
                val selected=editor.selected
                val hit=selected?.let { handles(rect(editor.boxes[it])).minByOrNull { (_,h)->hypot(h.x-event.x,h.y-event.y) } }?.takeIf { (_,h)->hypot(h.x-event.x,h.y-event.y)<=dp(24) }
                index=if(hit!=null) selected else editor.boxes.indices.reversed().firstOrNull { rect(editor.boxes[it]).contains(event.x,event.y) }
                handle=hit?.first.orEmpty()
                if(index!=null) {editor.select(index);startBox=editor.boxes[index!!];working=editor.boxes;mode=if(hit!=null) "resize" else "move"}
                else if(adding&&editor.boxes.size<80&&target.contains(event.x,event.y)) {editor.select(null);index=editor.boxes.size;working=editor.boxes+Box(p.x.toInt(),p.y.toInt(),1,1);mode="draw"}
                else {editor.select(null);mode="pan"}
            }
            MotionEvent.ACTION_MOVE -> {
                if(mode=="pan") {viewport.panX=panStartX+event.x-downX;viewport.panY=panStartY+event.y-downY;clampPan()}
                else {
                    val i=index ?: return true
                    val old=startBox;val dx=(p.x-start.x).roundToInt();val dy=(p.y-start.y).roundToInt()
                    val box=when(mode) {
                        "move" -> old!!.copy(x=(old.x+dx).coerceIn(0,image.width-old.width),y=(old.y+dy).coerceIn(0,image.height-old.height))
                        "resize" -> {
                            var l=old!!.x;var t=old.y;var r=l+old.width;var b=t+old.height
                            if('w' in handle) l=(old.x+dx).coerceIn(0,r-1)
                            if('e' in handle) r=(old.x+old.width+dx).coerceIn(l+1,image.width)
                            if('n' in handle) t=(old.y+dy).coerceIn(0,b-1)
                            if('s' in handle) b=(old.y+old.height+dy).coerceIn(t+1,image.height)
                            Box(l,t,r-l,b-t)
                        }
                        "draw" -> {val l=min(start.x,p.x).toInt().coerceIn(0,image.width-1);val t=min(start.y,p.y).toInt().coerceIn(0,image.height-1);Box(l,t,max(1,abs(p.x-start.x).roundToInt()).coerceAtMost(image.width-l),max(1,abs(p.y-start.y).roundToInt()).coerceAtMost(image.height-t))}
                        else -> return true
                    }
                    working=working!!.toMutableList().apply {set(i,box)}
                }
            }
            MotionEvent.ACTION_UP -> {
                val value=working
                if(value!=null && (mode!="draw" || (value.last().width>=12&&value.last().height>=12))) editor.commit(value,index)
                working=null;mode="";parent?.requestDisallowInterceptTouchEvent(false);performClick();changed()
            }
            MotionEvent.ACTION_CANCEL -> {working=null;mode="";parent?.requestDisallowInterceptTouchEvent(false)}
        }
        invalidate();return true
    }
    override fun performClick(): Boolean {super.performClick();return true}
    fun zoomBy(factor: Float) {viewport.zoom=(viewport.zoom*factor).coerceIn(1f,5f);clampPan();invalidate()}
    fun fit() {viewport.reset();invalidate()}
    private fun dp(n: Int)=n*resources.displayMetrics.density
}
