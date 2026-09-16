package com.eqo.pipeline

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import java.io.File
import java.io.FileInputStream

/**
 * Compatibility helper for setting data sources on [MediaExtractor].
 *
 * Direct filesystem paths passed to `setDataSource(path)` fail on Android 11+ (API 30+)
 * in mediaserver/extractor sandboxes. Passing an open [java.io.FileDescriptor] or using
 * [android.content.ContentResolver.openAssetFileDescriptor] bypasses native sandbox restrictions.
 */
object MediaExtractorCompat {

    fun setDataSource(extractor: MediaExtractor, context: Context, uri: Uri) {
        if (uri.scheme == "file" && uri.path != null) {
            val file = File(uri.path!!)
            FileInputStream(file).use { fis ->
                extractor.setDataSource(fis.fd, 0L, file.length())
            }
        } else {
            val opened = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.declaredLength < 0) {
                        extractor.setDataSource(afd.fileDescriptor)
                    } else {
                        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
                    }
                    true
                } ?: false
            }.getOrDefault(false)

            if (!opened) {
                extractor.setDataSource(context, uri, null)
            }
        }
    }
}
