package com.cyeam.seal.ocr

import org.junit.Assert.*
import org.junit.Test

class SegmentationTest {
    private fun grid(fg: Int=0xff111111.toInt(), bg: Int=0xffffffff.toInt()): IntArray = IntArray(120*120) { i ->
        val x=i%120; val y=i/120
        if((x in 12..38 || x in 72..98) && (y in 12..38 || y in 72..98)) fg else bg
    }
    @Test fun verticalReadsDownRightColumnFirst() {
        val boxes=Segmentation.segment(grid(),120,120,true,Ink.AUTO)
        assertEquals(4,boxes.size)
        assertTrue(boxes[0].x>boxes[2].x)
        assertTrue(boxes[0].y<boxes[1].y)
        assertTrue(boxes.all { it.x>=0 && it.y>=0 && it.x+it.width<=120 && it.y+it.height<=120 })
    }
    @Test fun horizontalReadsAcrossTopRowFirst() {
        val b=Segmentation.segment(grid(),120,120,false,Ink.AUTO)
        assertEquals(4,b.size); assertTrue(b[0].x<b[1].x); assertTrue(b[0].y<b[2].y)
    }
    @Test fun detectsWhiteTextOnDarkAndRedInk() {
        assertEquals(4,Segmentation.segment(grid(0xffffffff.toInt(),0xff222222.toInt()),120,120,true,Ink.AUTO).size)
        assertEquals(4,Segmentation.segment(grid(0xffbc241a.toInt()),120,120,true,Ink.RED).size)
        assertTrue(Segmentation.segment(grid(),120,120,true,Ink.RED).isEmpty())
    }
    @Test fun blankImagesDoNotProduceCharacters() {
        assertTrue(Segmentation.segment(IntArray(10000) { 0xffffffff.toInt() },100,100,true,Ink.AUTO).isEmpty())
    }
}
