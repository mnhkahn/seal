package com.cyeam.seal.history

import com.cyeam.seal.ocr.Candidate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val candidate = Candidate("粦", 0.8, "whole", work = "字帖", variants = listOf("㷠", "燐"))

    @Test fun persistsImageAndResultsAcrossStoreRecreation() {
        val directory = temporary.newFolder()
        val png = byteArrayOf(1, 2, 3, 4)
        HistoryStore(directory).add(png, listOf(candidate))
        val record = HistoryStore(directory).list().single()
        assertArrayEquals(png, record.image!!.readBytes())
        assertEquals(listOf(candidate), record.candidates)
        assertEquals("粦", record.text)
        assertTrue(record.time > 0)
        assertFalse(File(directory, "history.json").readText().contains("feedback_token"))
    }

    @Test fun countAndImageByteLimitsEvictOldImages() {
        val directory = temporary.newFolder()
        val store = HistoryStore(directory, limit = 2, maxImageBytes = 10)
        store.add(byteArrayOf(1, 2), listOf(candidate))
        val first = store.list().single()
        store.add(byteArrayOf(3, 4), listOf(candidate.copy(text = "女")))
        store.add(byteArrayOf(5, 6), listOf(candidate.copy(text = "男")))
        assertEquals(listOf("男", "女"), store.list().map { it.text })
        assertFalse(first.image!!.exists())
        val previous = store.list().mapNotNull { it.image }
        store.add(ByteArray(9), listOf(candidate))
        assertEquals(1, store.list().size)
        assertTrue(previous.none { it.exists() })
        assertEquals(1, File(directory, "history-images").listFiles()!!.size)
    }

    @Test fun deletingAndClearingRemoveImageFilesAndKeepLegacyText() {
        val directory = temporary.newFolder()
        File(directory, "history.json").writeText("""[{"time":123,"text":"旧记录"}]""")
        val store = HistoryStore(directory)
        assertNull(store.list().single().image)
        store.add(byteArrayOf(1), listOf(candidate))
        val fresh = store.list().first()
        assertEquals("旧记录", store.list().last().text)
        store.delete(fresh.id)
        assertFalse(fresh.image!!.exists())
        assertEquals("旧记录", store.list().single().text)
        store.add(byteArrayOf(2), listOf(candidate))
        val image = store.list().first().image!!
        store.clear()
        assertTrue(store.list().isEmpty())
        assertFalse(image.exists())
    }

    @Test fun failedIndexWritePreservesHistoryAndLeavesNoNewImage() {
        val directory = temporary.newFolder()
        val store = HistoryStore(directory)
        store.add(byteArrayOf(1), listOf(candidate))
        val original = store.list().single()
        File(directory, "history.tmp").mkdir()
        assertThrows(Exception::class.java) { store.add(byteArrayOf(2), listOf(candidate)) }
        assertEquals(original, store.list().single())
        assertArrayEquals(byteArrayOf(1), original.image!!.readBytes())
        assertEquals(1, File(directory, "history-images").listFiles()!!.size)
    }

    @Test fun clearRecoversDamagedIndexAndDeletesOrphanImages() {
        val directory = temporary.newFolder()
        val store = HistoryStore(directory)
        store.add(byteArrayOf(1), listOf(candidate))
        val image = store.list().single().image!!
        File(directory, "history.json").writeText("broken")
        assertThrows(Exception::class.java) { store.list() }
        store.clear()
        assertTrue(store.list().isEmpty())
        assertFalse(image.exists())
    }
}
