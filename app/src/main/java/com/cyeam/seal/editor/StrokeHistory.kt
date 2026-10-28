package com.cyeam.seal.editor

data class StrokePoint(val x: Float, val y: Float)

/** One pen-down / pen-up sequence is one stroke, regardless of its point count. */
class StrokeHistory {
    private val completed=mutableListOf<List<StrokePoint>>()
    val strokes: List<List<StrokePoint>> get()=completed.toList()
    val count get()=completed.size
    fun add(points: List<StrokePoint>) {
        if(points.isEmpty()) return
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f })
        completed+=points.toList()
    }
    fun undo(): Boolean { if(completed.isEmpty()) return false; completed.removeAt(completed.lastIndex); return true }
    fun clear() { completed.clear() }
}
