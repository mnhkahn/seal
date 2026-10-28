package com.cyeam.seal.ocr

import kotlin.math.max
import kotlin.math.min

/** Pixel-space bounds. Projection segmentation follows the site's row/column workflow. */
data class Box(val x: Int, val y: Int, val width: Int, val height: Int)
enum class Ink { AUTO, DARK, LIGHT, RED }
object Segmentation {
    fun segment(pixels: IntArray, width: Int, height: Int, vertical: Boolean, ink: Ink): List<Box> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val border = pixels.indices.filter { it % width < 3 || it % width >= width - 3 || it / width < 3 || it / width >= height - 3 }
        fun luma(p: Int) = ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000
        val lightBackground = border.map { luma(pixels[it]) }.average() >= 128
        val mask = BooleanArray(pixels.size) { i ->
            val p = pixels[i]; val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
            val visible = (p ushr 24) > 32
            visible && when (ink) {
                Ink.RED -> r > 90 && r > g * 1.35 && r > b * 1.35
                Ink.DARK -> luma(p) < 135
                Ink.LIGHT -> luma(p) > 160
                Ink.AUTO -> if (lightBackground) luma(p) < 140 else luma(p) > 160
            }
        }
        fun runs(counts: IntArray, threshold: Int, gap: Int): List<IntRange> {
            val result = mutableListOf<IntRange>(); var start = -1; var last = -1
            for (i in counts.indices) if (counts[i] > threshold) {
                if (start < 0) start = i
                if (last >= 0 && i - last > gap) { result += start..last; start = i }
                last = i
            }
            if (start >= 0) result += start..last
            return result.filter { it.last - it.first >= 3 }
        }
        val majorSize = if (vertical) width else height
        val minorSize = if (vertical) height else width
        val majorCounts = IntArray(majorSize)
        for (y in 0 until height) for (x in 0 until width) if (mask[y * width + x]) majorCounts[if(vertical) x else y]++
        val major = runs(majorCounts, max(1, minorSize / 250), max(3, majorSize / 100))
        val boxes = mutableListOf<Box>()
        for (band in if (vertical) major.reversed() else major) {
            val minorCounts = IntArray(minorSize)
            for (a in band) for (b in 0 until minorSize) {
                val index = if (vertical) b * width + a else a * width + b
                if(mask[index]) minorCounts[b]++
            }
            val characters = runs(minorCounts, max(1, band.count() / 150), max(3, minorSize / 100))
            for (char in characters) {
                var left = width; var right = 0; var top = height; var bottom = 0
                for (a in band) for (b in char) {
                    val x = if(vertical) a else b; val y = if(vertical) b else a
                    if (mask[y * width + x]) { left = min(left,x); right = max(right,x); top = min(top,y); bottom = max(bottom,y) }
                }
                if (right - left < 4 || bottom - top < 4) continue
                val pad = max(3, min(right-left,bottom-top) / 12)
                val x = max(0,left-pad); val y = max(0,top-pad)
                boxes += Box(x,y,min(width,right+pad+1)-x,min(height,bottom+pad+1)-y)
                if (boxes.size == 80) return boxes
            }
        }
        return boxes
    }
}
