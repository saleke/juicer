package com.eqo.reclaim

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RollbackCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cache: RollbackCache

    @Before
    fun setUp() {
        cache = RollbackCache(tmp.newFolder("reclaim"))
    }

    private fun createEntry(
        name: String = "my video.mp4",
        uri: String = "content://media/external/video/media/42",
        mime: String = "video/mp4",
        bytes: ByteArray = ByteArray(1024) { 7 },
    ): RollbackCache.Entry =
        cache.create(name, uri, mime, ByteArrayInputStream(bytes), backedUpAtMs = 1_000L)

    @Test
    fun `create persists a file and metadata entry`() {
        val entry = createEntry()
        assertEquals(1024L, entry.sizeBytes)
        val file = cache.fileFor(entry.id)
        assertNotNull(file)
        assertTrue(file!!.listFiles()!!.any { it.name == "meta.txt" })
        assertTrue(cache.entries().size == 1)
    }

    @Test
    fun `entry survives a cache reload from the same root`() {
        val entry = createEntry()
        val reloaded = RollbackCache(cache.fileFor(entry.id)!!.parentFile!!)
        val found = reloaded.entries().firstOrNull { it.id == entry.id }
        assertNotNull(found)
        assertEquals("my video.mp4", found!!.displayName)
        assertEquals("content://media/external/video/media/42", found.originalUri)
        assertEquals("video/mp4", found.sourceMime)
        assertEquals(1024L, found.sizeBytes)
        assertEquals(1_000L, found.backedUpAtMs)
    }

    @Test
    fun `display names with equals signs and unicode survive the metadata round trip`() {
        val weird = "süper =clip (100%).mp4"
        val entry = cache.create(weird, "file:///sdcard/a=/b.mp4", "video/avc", ByteArrayInputStream(ByteArray(3)), 1L)
        val found = cache.entries().firstOrNull { it.id == entry.id }
        assertNotNull(found)
        assertEquals(weird, found!!.displayName)
        assertEquals("file:///sdcard/a=/b.mp4", found.originalUri)
    }

    @Test
    fun `safe extension strips path separators and keeps letters digits`() {
        val entry = cache.create("evil../injection;rm.mp4", "uri", null, ByteArrayInputStream(ByteArray(1)), 1L)
        val dir = cache.fileFor(entry.id)!!
        // The id is a plain dir name; the copy must not escape the entry dir.
        val copied = dir.listFiles()!!.first { it.name != "meta.txt" }
        assertEquals("original.mp4", copied.name)
    }

    @Test
    fun `totalBytes sums the copies`() {
        createEntry(name = "a.mp4", bytes = ByteArray(1))
        createEntry(name = "b.mp4", bytes = ByteArray(2048))
        assertEquals(2049L, cache.totalBytes())
    }

    @Test
    fun `purge removes one entry and is idempotent`() {
        val a = createEntry(name = "a.mp4")
        createEntry(name = "b.mp4")
        assertTrue(cache.purge(a.id))
        assertFalse(cache.purge(a.id))
        assertEquals(1, cache.entries().size)
    }

    @Test
    fun `purge all empties the cache`() {
        createEntry(name = "a.mp4")
        createEntry(name = "b.mp4")
        assertEquals(2, cache.purgeAll())
        assertEquals(0, cache.entries().size)
        assertEquals(0, cache.purgeAll())
    }

    @Test
    fun `unknown ids and malformed metas are handled`() {
        assertNull(cache.fileFor("does-not-exist"))
        assertFalse(cache.purge("does-not-exist"))
        // A dir without meta.txt is skipped, not counted.
        cache.fileFor(createEntry().id)!!.let { dir ->
            java.io.File(dir, "meta.txt").delete()
        }
        assertEquals(0, cache.entries().size)
    }

    @Test
    fun `backedUpAtMs round-trips through retention policy`() {
        val entry = createEntry(name = "old.mp4", bytes = ByteArray(64))
        val now = 1_000L + ReclaimMath.DEFAULT_ROLLBACK_RETENTION_MS
        assertTrue(ReclaimMath.shouldAutoPurge(entry.backedUpAtMs, now))
    }
}