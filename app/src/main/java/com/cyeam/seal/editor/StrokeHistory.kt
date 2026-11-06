package com.cyeam.seal.editor

data class StrokePoint(val x: Float, val y: Float)

/** One pen-down / pen-up sequence is one stroke, regardless of its point count. */
class StrokeHistory {
    val defaultWidth = .05f
    private val widths = mutableListOf<Float>()
    private val completed=mutableListOf<List<StrokePoint>>()
    val strokes: List<List<StrokePoint>> get()=completed.toList()
    val count get()=completed.size
    fun widthAt(index: Int): Float = widths[index]
    fun add(points: List<StrokePoint>, width: Float = defaultWidth) {
        if(points.isEmpty()) return
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f })
        require(width.isFinite() && width > 0f && width <= 1f)
        completed+=points.toList()
        widths+=width
    }
    fun undo(): Boolean { if(completed.isEmpty()) return false; completed.removeAt(completed.lastIndex); widths.removeAt(widths.lastIndex); return true }
    fun clear() { completed.clear(); widths.clear() }
}
