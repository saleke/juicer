package com.eqo.reclaim

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.eqo.reclaim.ReclaimMath.isEqoOutput

/**
 * Feature 2 — the batch compression gallery scanner.
 *
 * Discovers heavy video candidates via `MediaStore.Video.Media` and sorts by
 * reclaimable bytes (descending). The SQL `ORDER BY SIZE DESC` is equivalent
 * to the reclaimable order because [ReclaimMath.reclaimableBytes] is monotone
 * in size for a fixed duration (see its KDoc invariant).
 *
 * Exclusions:
 *  - our own outputs (`eqo_*`, never re-encode our own work),
 *  - zero-size rows,
 *  - rows another app holds pending mid-write (`IS_PENDING`, API 29+),
 *  - trashed rows (`IS_TRASHED`, API 30+),
 *  - rows with no duration (unreadable / not yet finalized).
 *
 * Permissions: querying non-owned media requires `READ_MEDIA_VIDEO` (API 33+)
 * or `READ_EXTERNAL_STORAGE` (API 29–32); owned rows (app outputs) are always
 * visible. The UI must request the permission before calling [scan]; the
 * scanner itself stays defensive against injectable providers.
 */
object VideoGalleryScanner {

    private const val TAG = "eqo.Scanner"

    /** Safety cap on list size; reclaimable order is stable for the top-N. */
    const val MAX_CANDIDATES = 500

    /** The runtime permission required to read non-owned media on this SDK. */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(context: android.content.Context): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Scans the device for compressible video candidates.
     *
     * @param targetFraction Learned/recommended fraction for the estimate
     *   (defaults to [ReclaimMath.DEFAULT_TARGET_FRACTION]).
     * @param limit          Upper bound on returned candidates.
     */
    fun scan(
        resolver: ContentResolver,
        targetFraction: Float = ReclaimMath.DEFAULT_TARGET_FRACTION,
        limit: Int = MAX_CANDIDATES,
    ): List<VideoCandidate> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
        )

        val selection = StringBuilder(
            "${MediaStore.Video.Media.SIZE} > 0 AND " +
                "${MediaStore.Video.Media.MIME_TYPE} LIKE 'video/%' AND " +
                "${MediaStore.Video.Media.DISPLAY_NAME} NOT LIKE 'eqo\\_%' ESCAPE '\\'",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection.append(" AND ${MediaStore.Video.Media.IS_PENDING} = 0")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            selection.append(" AND ${MediaStore.Video.Media.IS_TRASHED} = 0")
        }

        val candidates = mutableListOf<VideoCandidate>()
        runCatching {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection.toString(),
                null,
                "${MediaStore.Video.Media.SIZE} DESC",
                // The provider may ignore the limit on old APIs; enforce it client-side too.
                android.os.CancellationSignal(),
            )
        }.onSuccess { cursor ->
            cursor?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val wIdx = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val hIdx = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val mimeIdx = c.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val bucketIdx = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val addedIdx = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)

                while (c.moveToNext() && candidates.size < limit) {
                    runCatching {
                        val id = c.getLong(idIdx)
                        val name = c.getString(nameIdx) ?: ""
                        val size = c.getLong(sizeIdx)
                        val durationUs = c.getLong(durIdx)
                        if (name.isBlank() || isEqoOutput(name) || size <= 0 || durationUs <= 0) {
                            return@runCatching
                        }
                        candidates += VideoCandidate(
                            id = id,
                            uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()),
                            displayName = name,
                            sizeBytes = size,
                            durationUs = durationUs,
                            width = if (wIdx >= 0 && !c.isNull(wIdx)) c.getInt(wIdx) else null,
                            height = if (hIdx >= 0 && !c.isNull(hIdx)) c.getInt(hIdx) else null,
                            mimeType = if (mimeIdx >= 0) c.getString(mimeIdx) else null,
                            bucket = if (bucketIdx >= 0) c.getString(bucketIdx) else null,
                            dateAddedMs = if (addedIdx >= 0) c.getLong(addedIdx) * 1000L else 0L,
                        )
                    }.onFailure { t ->
                        Log.w(TAG, "skipped a scanner row: ${t.message}")
                    }
                }
            }
        }.onFailure { t ->
            Log.e(TAG, "MediaStore scan failed: ${t.message}")
        }
        Log.i(TAG, "scan: ${candidates.size} candidates (limit=$limit, fraction=$targetFraction)")
        return candidates.sortedByDescending { it.reclaimableBytes(targetFraction) }
    }
}

/**
 * A single scan result. [reclaimableBytes]/[savedPercent] are derived via the
 * shared [ReclaimMath] estimate so the list reflects the same math the encoder
 * will actually budget with.
 */
data class VideoCandidate(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationUs: Long,
    val width: Int?,
    val height: Int?,
    val mimeType: String?,
    val bucket: String?,
    val dateAddedMs: Long,
) {
    val durationSeconds: Int get() = (durationUs / 1_000_000L).toInt()
    val resolution: String?
        get() = if (width != null && height != null) "${width}x${height}" else null

    fun reclaimableBytes(targetFraction: Float = ReclaimMath.DEFAULT_TARGET_FRACTION): Long =
        ReclaimMath.reclaimableBytes(sizeBytes, durationUs, targetFraction)

    fun savedPercent(targetFraction: Float = ReclaimMath.DEFAULT_TARGET_FRACTION): Float =
        ReclaimMath.savedPercent(sizeBytes, reclaimableBytes(targetFraction))
}