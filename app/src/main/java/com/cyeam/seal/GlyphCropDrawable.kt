package com.cyeam.seal

import android.graphics.*
import android.graphics.drawable.Drawable
import com.cyeam.seal.ocr.Box

/** Preview a source rectangle without allocating a full bitmap per result row. */
class GlyphCropDrawable(private val bitmap: Bitmap, private val crop: Box) : Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source=Rect(crop.x,crop.y,crop.x+crop.width,crop.y+crop.height)
    override fun draw(canvas: Canvas) { canvas.drawBitmap(bitmap,source,bounds,paint) }
    override fun getIntrinsicWidth()=crop.width
    override fun getIntrinsicHeight()=crop.height
    override fun setAlpha(alpha: Int) { paint.alpha=alpha;invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
