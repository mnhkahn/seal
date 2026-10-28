package com.cyeam.seal.editor
import org.junit.Assert.*
import org.junit.Test

class StrokeHistoryTest {
    @Test fun repeatedUndoRemovesExactlyTheMostRecentCompleteStroke() {
        val h=StrokeHistory()
        val first=listOf(StrokePoint(.1f,.2f),StrokePoint(.8f,.2f))
        val second=listOf(StrokePoint(.5f,.2f),StrokePoint(.5f,.8f))
        val third=listOf(StrokePoint(.1f,.8f),StrokePoint(.9f,.8f))
        h.add(first);h.add(second);h.add(third)
        assertTrue(h.undo());assertEquals(listOf(first,second),h.strokes)
        assertTrue(h.undo());assertEquals(listOf(first),h.strokes)
        assertTrue(h.undo());assertEquals(0,h.count)
        assertFalse(h.undo());assertEquals(0,h.count)
    }
    @Test fun preservesDotStrokesAndDoesNotRecordEmptyOrMutableDrafts() {
        val h=StrokeHistory();h.add(emptyList());assertEquals(0,h.count)
        val draft=mutableListOf(StrokePoint(.3f,.4f));h.add(draft);draft.clear()
        assertEquals(1,h.count);assertEquals(1,h.strokes.single().size)
        h.clear();assertFalse(h.undo())
    }
}
