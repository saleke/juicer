package com.eqo.reclaim

import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Feature 3 — the atomic storage reclamation rollback cache.
 *
 * Original video files are preserved in **app-private storage** before their
 * gallery rows are retired, so a reclaim is reversible by construction: the
 * byte cost of the rollback is charged to the app until the user (or the
 * retention policy, see [com.eqo.reclaim.ReclaimMath.shouldAutoPurge]) purges
 * the entry.
 *
 * Layout (root is `context.filesDir/reclaim`):
 * ```
 * <root>/<id>/original.<ext>   # full copy of the pre-reclaim file
 * <root>/<id>/meta.txt         # URL-encoded key=value location metadata
 * ```
 *
 * Pure `java.io` by design (the root is injected) so it is unit-testable on
 * the JVM without Robolectric. The actual copy stream comes from the caller.
 *
 * Threading: instances are owned by the ViewModel; callers route through a
 * single I/O dispatcher or the drain thread. [entries]/[totalBytes] read the
 * directory tree and are safe to call repeatedly; purge is idempotent.
 */
class RollbackCache(private val root: File) {

    data class Entry(
        val id: String,
        val displayName: String,
        val originalUri: String,
        val sourceMime: String?,
        val sizeBytes: Long,
        val backedUpAtMs: Long,
    )

    companion object {
        private val UTF8 = StandardCharsets.UTF_8
        private const val META_NAME = "meta.txt"
        private const val KEY_SUB = "id"
        private const val KEY_NAME = "displayName"
        private const val KEY_URI = "originalUri"
        private const val KEY_MIME = "sourceMime"
        private const val KEY_SIZE = "sizeBytes"
        private const val KEY_AT = "backedUpAtMs"
        private const val ORIGINAL_EXT = "dat"

        private fun encode(value: String): String = URLEncoder.encode(value, UTF8.name())
        private fun decode(value: String): String = URLDecoder.decode(value, UTF8.name())
    }

    /**
     * Creates a rollback entry and copies [copyFrom] into it. Returns the
     * persisted entry. The caller must close [copyFrom]; this writes a safe
     * extension derived from [displayName] (letters/digits only, else "dat").
     */
    fun create(
        displayName: String,
        originalUri: String,
        sourceMime: String?,
        copyFrom: InputStream,
        backedUpAtMs: Long = System.currentTimeMillis(),
    ): Entry {
        val id = "${backedUpAtMs}_${(Math.random() * 1_000_000).toInt()}"
        val dir = entryDir(id)
        check(dir.mkdirs()) { "could not create rollback dir $dir" }
        val ext = safeExtension(displayName)
        val file = File(dir, "original.$ext")
        val size = copyFrom.use { input ->
            file.outputStream().use { out ->
                input.copyTo(out, bufferSize = 1 shl 16)
            }
        }
        val entry = Entry(
            id = id,
            displayName = displayName,
            originalUri = originalUri,
            sourceMime = sourceMime,
            sizeBytes = size,
            backedUpAtMs = backedUpAtMs,
        )
        writeMeta(entry)
        return entry
    }

    /** All persisted entries, oldest last. Skips malformed/corrupted dirs. */
    fun entries(): List<Entry> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { dir -> readMeta(dir) }

    /** Byte weight of the preserved originals (excludes metadata overhead). */
    fun totalBytes(): Long {
        var total = 0L
        (root.listFiles { f -> f.isDirectory } ?: emptyArray()).forEach { dir ->
            (dir.listFiles() ?: emptyArray()).forEach { f ->
                if (f.name != META_NAME) total += f.length()
            }
        }
        return total
    }

    /** Directory backing an entry, or null when unknown/id empty. */
    fun fileFor(id: String): File? = entryDir(id).takeIf { it.exists() && it.isDirectory }

    /** Removes one entry (dir + contents). Idempotent. */
    fun purge(id: String): Boolean {
        val dir = fileFor(id) ?: return false
        val ok = dir.deleteRecursively()
        return ok || !dir.exists()
    }

    /** Removes every entry; returns the number purged. */
    fun purgeAll(): Int {
        val dirs = root.listFiles { f -> f.isDirectory } ?: emptyArray()
        dirs.forEach { it.deleteRecursively() }
        return dirs.size
    }

    // ------------------------------------------------------------ internals

    private fun entryDir(id: String): File {
        check(id.isNotBlank()) { "empty rollback id" }
        check(!id.contains(File.separatorChar)) { "rollback id must be a plain dir name" }
        return File(root, id)
    }

    private fun safeExtension(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        if (dot < 0 || dot == displayName.length - 1) return ORIGINAL_EXT
        val raw = displayName.substring(dot + 1)
        return raw.filter { it.isLetterOrDigit() }.ifEmpty { ORIGINAL_EXT }.take(8)
    }

    private fun writeMeta(entry: Entry) {
        val meta = File(entryDir(entry.id), META_NAME)
        meta.writeText(
            listOf(
                "${KEY_SUB}=${encode(entry.id)}",
                "${KEY_NAME}=${encode(entry.displayName)}",
                "${KEY_URI}=${encode(entry.originalUri)}",
                "${KEY_MIME}=${encode(entry.sourceMime ?: "")}",
                "${KEY_SIZE}=${entry.sizeBytes}",
                "${KEY_AT}=${entry.backedUpAtMs}",
            ).joinToString("\n"),
            UTF8,
        )
    }

    private fun readMeta(dir: File): Entry? {
        val meta = File(dir, META_NAME)
        if (!meta.isFile) return null
        return runCatching {
            val map = meta.readLines(UTF8).mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
            }.toMap()
            Entry(
                id = decode(map.getValue(KEY_SUB)),
                displayName = decode(map.getValue(KEY_NAME)),
                originalUri = decode(map.getValue(KEY_URI)),
                sourceMime = decode(map[KEY_MIME] ?: "").ifEmpty { null },
                sizeBytes = map[KEY_SIZE]?.toLong() ?: 0L,
                backedUpAtMs = map[KEY_AT]?.toLong() ?: 0L,
            )
        }.getOrNull()
    }
}