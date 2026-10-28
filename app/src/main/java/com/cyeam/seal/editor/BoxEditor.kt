package com.cyeam.seal.editor

import com.cyeam.seal.ocr.Box

/** Immutable snapshots make a completed drag or split exactly one undo step. */
class BoxEditor {
    data class Snapshot(val boxes: List<Box>, val selected: Int?)
    var boxes: List<Box> = emptyList(); private set
    var selected: Int? = null; private set
    private val history = ArrayDeque<Snapshot>()
    val canUndo get() = history.isNotEmpty()
    fun reset(value: List<Box>) { boxes=value.toList(); selected=boxes.indices.firstOrNull(); history.clear() }
    fun select(index: Int?) { selected=index?.takeIf { it in boxes.indices } }
    fun commit(value: List<Box>, index: Int? = selected): Boolean {
        require(value.size<=80 && value.all { it.x>=0 && it.y>=0 && it.width>0 && it.height>0 })
        if(value==boxes) { select(index); return false }
        history.addLast(Snapshot(boxes,selected)); if(history.size>40) history.removeFirst()
        boxes=value.toList(); select(index); return true
    }
    fun split(horizontal: Boolean) {
        val i=selected ?: return; val b=boxes[i]
        val length=if(horizontal) b.width else b.height
        if(length<24 || boxes.size>=80) return
        val half=length/2
        val first=if(horizontal) b.copy(width=half) else b.copy(height=half)
        val second=if(horizontal) b.copy(x=b.x+half,width=b.width-half) else b.copy(y=b.y+half,height=b.height-half)
        commit(boxes.take(i)+listOf(first,second)+boxes.drop(i+1),i)
    }
    fun delete() { val i=selected ?: return; commit(boxes.filterIndexed { n,_ -> n!=i },if(boxes.size>1) i.coerceAtMost(boxes.size-2) else null) }
    fun undo() { val previous=history.removeLastOrNull() ?: return; boxes=previous.boxes; selected=previous.selected }
}

class BoxViewport {
    var zoom=1f; var panX=0f; var panY=0f
    fun reset() { zoom=1f; panX=0f; panY=0f }
}
