package com.cyeam.seal.editor
import com.cyeam.seal.ocr.Box
import org.junit.Assert.*
import org.junit.Test

class BoxEditorTest {
    @Test fun bothSplitDirectionsPreserveOddPixelBoundsAndUndoAtomically() {
        val e=BoxEditor();val original=Box(12,18,101,81);e.reset(listOf(original))
        e.split(true)
        assertEquals(listOf(Box(12,18,50,81),Box(62,18,51,81)),e.boxes)
        e.select(1);e.split(false)
        assertEquals(listOf(Box(12,18,50,81),Box(62,18,51,40),Box(62,58,51,41)),e.boxes)
        e.undo();assertEquals(2,e.boxes.size)
        e.undo();assertEquals(listOf(original),e.boxes);assertFalse(e.canUndo)
    }
    @Test fun dragIsOneCommitAndUndoRestoresOriginalBounds() {
        val e=BoxEditor();val original=Box(0,0,50,50);e.reset(listOf(original))
        e.commit(listOf(Box(15,23,60,70)),0);e.undo();assertEquals(listOf(original),e.boxes)
        assertFalse(e.commit(listOf(original)));assertFalse(e.canUndo)
    }
    @Test fun deletionAndNewImageDoNotLeaveInvalidSelectionOrOldUndo() {
        val e=BoxEditor();e.reset(listOf(Box(0,0,20,30)))
        e.delete();assertTrue(e.boxes.isEmpty());assertNull(e.selected)
        e.undo();assertEquals(1,e.boxes.size)
        e.reset(listOf(Box(20,20,40,40)));assertFalse(e.canUndo)
    }
    @Test fun tinyBoxesAndTheEditorLimitDoNotProduceInvalidSplits() {
        val e=BoxEditor();e.reset(listOf(Box(0,0,12,12)));e.split(true);assertEquals(1,e.boxes.size)
        e.reset(List(80) { Box(it*40,0,40,40) });e.split(false);assertEquals(80,e.boxes.size)
    }
}
