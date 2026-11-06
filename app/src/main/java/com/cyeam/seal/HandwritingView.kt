package com.cyeam.seal

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.cyeam.seal.editor.StrokeHistory
import com.cyeam.seal.editor.StrokePoint

class HandwritingView(context: Context, private val history: StrokeHistory) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val guides = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD8B4A0.toInt()
        strokeWidth = resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val activePath = Path()
    private val previewPath = Path()
    private var active = mutableListOf<StrokePoint>()
    private var pointer = -1
    private var committed: Bitmap? = null
    private var committedCanvas: Canvas? = null
    private var cacheDirty = true
    // Widths are relative to the canvas, so rotation and export preserve the drawing.
    var penWidth: Float = history.defaultWidth
    private var activeWidth = penWidth
    var onChanged: (() -> Unit)? = null

    init { contentDescription = context.getString(R.string.handwriting_single); isFocusable = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = minOf(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(side, side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        committed = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
        committedCanvas = committed?.let { Canvas(it) }
        cacheDirty = true
        rebuildActivePath()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFFFFFAF2.toInt())
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), guides)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, guides)
        canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), guides)
        canvas.drawLine(width.toFloat(), 0f, 0f, height.toFloat(), guides)
        if (cacheDirty) {
            committed?.eraseColor(Color.TRANSPARENT)
            committedCanvas?.let { drawHistory(it, width.toFloat(), height.toFloat()) }
            cacheDirty = false
        }
        committed?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        drawActive(canvas)
    }

    private fun drawHistory(canvas: Canvas, w: Float, h: Float) {
        history.strokes.forEachIndexed { index, points ->
            val path = Path()
            buildPath(path, points, w, h)
            drawStroke(canvas, path, points, history.widthAt(index), w, h)
        }
    }

    private fun buildPath(path: Path, points: List<StrokePoint>, w: Float, h: Float) {
        path.reset()
        if (points.isEmpty()) return
        path.moveTo(points[0].x * w, points[0].y * h)
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            path.quadTo(previous.x * w, previous.y * h,
                (previous.x + current.x) * w / 2, (previous.y + current.y) * h / 2)
        }
    }

    private fun drawStroke(canvas: Canvas, path: Path, points: List<StrokePoint>, thickness: Float, w: Float, h: Float) {
        val last = points.lastOrNull() ?: return
        ink.strokeWidth = minOf(w, h) * thickness
        // Always render through the actual contact point; midpoint smoothing alone trails the finger.
        previewPath.set(path)
        previewPath.lineTo(last.x * w, last.y * h)
        canvas.drawPath(previewPath, ink)
        ink.style = Paint.Style.FILL
        canvas.drawCircle(points[0].x * w, points[0].y * h, ink.strokeWidth / 2, ink)
        ink.style = Paint.Style.STROKE
    }

    private fun drawActive(canvas: Canvas) =
        drawStroke(canvas, activePath, active, activeWidth, width.toFloat(), height.toFloat())

    private fun rebuildActivePath() = buildPath(activePath, active, width.toFloat(), height.toFloat())

    private fun append(x: Float, y: Float) {
        if (width <= 0 || height <= 0) return
        val point = StrokePoint((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))
        val previous = active.lastOrNull()
        if (point == previous) return
        if (previous == null) activePath.moveTo(point.x * width, point.y * height)
        else activePath.quadTo(previous.x * width, previous.y * height,
            (previous.x + point.x) * width / 2, (previous.y + point.y) * height / 2)
        active.add(point)
    }

    private fun appendEvent(event: MotionEvent, index: Int) {
        for (i in 0 until event.historySize) {
            append(event.getHistoricalX(index, i), event.getHistoricalY(index, i))
        }
        append(event.getX(index), event.getY(index))
    }

    private fun finishStroke() {
        if (pointer < 0) return
        // Bring the cache up to date before appending, even if no frame was drawn since undo/resize.
        if (!cacheDirty) committedCanvas?.let { drawActive(it) }
        history.add(active, activeWidth)
        resetActive()
        onChanged?.invoke()
    }

    private fun resetActive() { active.clear(); activePath.reset(); pointer = -1 }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                requestUnbufferedDispatch(event)
                resetActive()
                pointer = event.getPointerId(0)
                activeWidth = penWidth
                append(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index >= 0) appendEvent(event, index)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == pointer) {
                    appendEvent(event, index)
                    finishStroke()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                resetActive()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    fun undo() { resetActive(); history.undo(); cacheDirty = true; invalidate(); onChanged?.invoke() }
    fun clear() { resetActive(); history.clear(); cacheDirty = true; invalidate(); onChanged?.invoke() }
    fun exportSingleCharacter(): Bitmap {
        check(history.count > 0)
        return Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            drawHistory(canvas, 600f, 600f)
        }
    }
}
