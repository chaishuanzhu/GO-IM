package com.goim.kmp.storage

import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalMediaStoreTest {
    private val fs = FakeFileSystem()
    private val home = UserHome("alice", "/GOIM/Users/alice".toPath())
    private val store = LocalMediaStore(home, fs)

    @Test
    fun stageKeepsExtensionAndName() {
        val id = store.stage(byteArrayOf(1, 2, 3), "报告.pdf")
        assertTrue(id.startsWith("local:"))
        val path = store.path(id)!!
        assertEquals(home.tmp, path.parent)
        assertTrue(path.name.endsWith(".pdf"))
        assertEquals("报告.pdf", store.displayName(id))
        assertContentEquals(byteArrayOf(1, 2, 3), store.bytes(id))
    }

    @Test
    fun promoteCopiesIntoMediaOrFilesUnderRemoteId() {
        val img = store.stage(byteArrayOf(9), "a.jpg")
        store.promote(img, "1001", "image/jpeg")
        assertEquals("/GOIM/Users/alice/media/1001.jpg", store.path("1001").toString())
        // 暂存副本保留到显式删除
        assertTrue(store.path(img) != null)
        store.remove(img)
        assertNull(store.path(img))

        val doc = store.stage(byteArrayOf(7), "doc.docx")
        store.promote(doc, "1002", "application/zip")
        assertEquals("/GOIM/Users/alice/files/1002.docx", store.path("1002").toString())
        assertEquals("doc.docx", store.displayName("1002"))
    }

    @Test
    fun downloadCommitsAtomicallyAndReplacesOld() {
        val part = store.downloadTarget("55")
        fs.sink(part).buffer().use { it.write(byteArrayOf(4, 5)) }
        assertNull(store.path("55"), ".part 不算已下载")
        val dest = store.commitDownload("55", part, "clip.mp4")
        assertEquals("/GOIM/Users/alice/files/55.mp4", dest.toString())
        assertContentEquals(byteArrayOf(4, 5), store.bytes("55"))
        assertTrue(!fs.exists(part))

        val again = store.downloadTarget("55")
        fs.sink(again).buffer().use { it.write(byteArrayOf(6)) }
        store.commitDownload("55", again, "clip.mp4")
        assertContentEquals(byteArrayOf(6), store.bytes("55"))
    }

    @Test
    fun diskKeyAndExtensionSanitizing() {
        assertEquals("ABC-1", LocalMediaStore.diskKey("local:ABC-1"))
        assertEquals("a_b", LocalMediaStore.diskKey("a/b"))
        assertEquals("", LocalMediaStore.fileExtension("noext"))
        assertEquals("m4a", LocalMediaStore.fileExtension("voice.M4A"))
        assertTrue(LocalMediaStore.isMedia("audio/mp4") && !LocalMediaStore.isMedia("application/pdf"))
    }
}
