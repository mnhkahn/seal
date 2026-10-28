package com.cyeam.seal

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.cyeam.seal.editor.StrokeHistory
import com.cyeam.seal.editor.StrokePoint

class HandwritingView(context: Context, private val history: StrokeHistory) : View(context) {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private val guides=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFFD8B4A0.toInt(); style=Paint.Style.STROKE; strokeWidth=resources.displayMetrics.density; pathEffect=DashPathEffect(floatArrayOf(8f,8f),0f) }
    private val path=Path()
    private var active=mutableListOf<StrokePoint>()
    private var pointer=-1
    var onChanged: (() -> Unit)?=null
    init { contentDescription=context.getString(R.string.handwriting_single); isFocusable=true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.WHITE)
        canvas.drawLine(width/2f,0f,width/2f,height.toFloat(),guides)
        canvas.drawLine(0f,height/2f,width.toFloat(),height/2f,guides)
        canvas.drawLine(0f,0f,width.toFloat(),height.toFloat(),guides)
        canvas.drawLine(width.toFloat(),0f,0f,height.toFloat(),guides)
        drawStrokes(canvas,width.toFloat(),height.toFloat(),history.strokes+listOf(active))
    }
    private fun drawStrokes(canvas: Canvas,w: Float,h: Float,strokes: List<List<StrokePoint>>) {
        ink.strokeWidth=minOf(w,h)*.025f
        for(stroke in strokes) {
            if(stroke.isEmpty()) continue
            if(stroke.size==1 || stroke.all { it==stroke[0] }) { ink.style=Paint.Style.FILL; canvas.drawCircle(stroke[0].x*w,stroke[0].y*h,ink.strokeWidth/2,ink); ink.style=Paint.Style.STROKE; continue }
            path.reset(); path.moveTo(stroke[0].x*w,stroke[0].y*h)
            for(p in stroke.drop(1)) path.lineTo(p.x*w,p.y*h)
            canvas.drawPath(path,ink)
        }
    }
    private fun append(x: Float,y: Float) { active+=StrokePoint((x/width).coerceIn(0f,1f),(y/height).coerceIn(0f,1f)) }
    private fun finishStroke(x: Float,y: Float) {
        if(pointer<0) return
        append(x,y);history.add(active);active=mutableListOf();pointer=-1;onChanged?.invoke()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); pointer=event.getPointerId(0); active=mutableListOf(); append(event.x,event.y) }
            MotionEvent.ACTION_MOVE -> {
                val index=event.findPointerIndex(pointer)
                if(index>=0) {
                    for(i in 0 until event.historySize) append(event.getHistoricalX(index,i),event.getHistoricalY(index,i))
                    append(event.getX(index),event.getY(index))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> { val i=event.actionIndex;if(event.getPointerId(i)==pointer) finishStroke(event.getX(i),event.getY(i)) }
            MotionEvent.ACTION_UP -> { finishStroke(event.x,event.y);parent?.requestDisallowInterceptTouchEvent(false);performClick() }
            MotionEvent.ACTION_CANCEL -> { active.clear(); pointer=-1; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun undo() { active.clear(); pointer=-1; history.undo(); invalidate(); onChanged?.invoke() }
    fun clear() { active.clear(); pointer=-1; history.clear(); invalidate(); onChanged?.invoke() }
    fun exportSingleCharacter(): Bitmap {
        check(history.count>0)
        return Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas=Canvas(bitmap); canvas.drawColor(Color.WHITE)
            // Export only black ink on white; guides are presentation-only.
            drawStrokes(canvas,600f,600f,history.strokes)
        }
    }
}
