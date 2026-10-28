package com.cyeam.seal

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.cyeam.seal.ocr.Box
import kotlin.math.*

/** Drag an edge/corner to resize; drag inside to move; drag outside to draw anew. */
class CropView(context: Context, private val bitmap: Bitmap, initial: Box?) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val target = RectF()
    private var scale = 1f
    private var leftOffset = 0f; private var topOffset = 0f
    private var startX = 0f; private var startY = 0f
    private var previous = RectF()
    private var mode = 0
    private var edgeX = 0; private var edgeY = 0
    private val rect = initial?.let { RectF(it.x.toFloat(),it.y.toFloat(),(it.x+it.width).toFloat(),(it.y+it.height).toFloat()) }
        ?: RectF(bitmap.width * .1f,bitmap.height * .1f,bitmap.width * .9f,bitmap.height * .9f)
    init { contentDescription = context.getString(R.string.crop_hint); isFocusable = true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scale = min(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
        leftOffset = (width-bitmap.width*scale)/2; topOffset = (height-bitmap.height*scale)/2
        target.set(leftOffset,topOffset,leftOffset+bitmap.width*scale,topOffset+bitmap.height*scale)
        canvas.drawColor(Color.rgb(240,235,226))
        canvas.drawBitmap(bitmap,null,target,paint)
        val r = RectF(leftOffset+rect.left*scale,topOffset+rect.top*scale,leftOffset+rect.right*scale,topOffset+rect.bottom*scale)
        paint.color = 0x66000000
        canvas.drawRect(target.left,target.top,target.right,r.top,paint)
        canvas.drawRect(target.left,r.bottom,target.right,target.bottom,paint)
        canvas.drawRect(target.left,r.top,r.left,r.bottom,paint)
        canvas.drawRect(r.right,r.top,target.right,r.bottom,paint)
        paint.color = Color.rgb(164,51,37); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3*resources.displayMetrics.density
        canvas.drawRect(r,paint); paint.style = Paint.Style.FILL
        for(x in listOf(r.left,r.right)) for(y in listOf(r.top,r.bottom)) canvas.drawCircle(x,y,7*resources.displayMetrics.density,paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = ((event.x-leftOffset)/scale).coerceIn(0f,bitmap.width.toFloat())
        val y = ((event.y-topOffset)/scale).coerceIn(0f,bitmap.height.toFloat())
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startX=x; startY=y; previous=RectF(rect)
                val tolerance=28*resources.displayMetrics.density/scale
                edgeX = if(abs(x-rect.left)<tolerance) -1 else if(abs(x-rect.right)<tolerance) 1 else 0
                edgeY = if(abs(y-rect.top)<tolerance) -1 else if(abs(y-rect.bottom)<tolerance) 1 else 0
                mode = if(edgeX!=0 || edgeY!=0) 1 else if(rect.contains(x,y)) 2 else 3
            }
            MotionEvent.ACTION_MOVE -> {
                when(mode) {
                    1 -> {
                        if(edgeX<0) rect.left = x.coerceAtMost(rect.right-2)
                        if(edgeX>0) rect.right = x.coerceAtLeast(rect.left+2)
                        if(edgeY<0) rect.top = y.coerceAtMost(rect.bottom-2)
                        if(edgeY>0) rect.bottom = y.coerceAtLeast(rect.top+2)
                    }
                    2 -> { rect.set(previous); rect.offset((x-startX).coerceIn(-previous.left,bitmap.width-previous.right),(y-startY).coerceIn(-previous.top,bitmap.height-previous.bottom)) }
                    3 -> rect.set(min(startX,x),min(startY,y),max(startX,x),max(startY,y))
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> { performClick(); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun box(): Box {
        val x = rect.left.roundToInt().coerceIn(0,bitmap.width-1); val y = rect.top.roundToInt().coerceIn(0,bitmap.height-1)
        return Box(x,y,(rect.right.roundToInt()-x).coerceIn(1,bitmap.width-x),(rect.bottom.roundToInt()-y).coerceIn(1,bitmap.height-y))
    }
}
