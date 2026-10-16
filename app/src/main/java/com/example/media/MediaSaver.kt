package com.example.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Where an exported file ended up. */
data class SavedMedia(
    /** Shareable `content://` URI from MediaStore, or null when the result is only [file]. */
    val uri: Uri?,
    /** The exported file inside the app (shareable through the FileProvider), if kept. */
    val file: File?,
    /** Human-readable location, e.g. "Movies/MST DUBBERS". */
    val location: String
)

object MediaSaver {
    const val ALBUM = "MST DUBBERS"

    /**
     * Copies an exported video into the public Movies/MST DUBBERS folder so it shows up in the
     * gallery. Android 10+ needs no permission for this; on older versions the file is kept in the
     * app's own Movies folder and can be shared from there.
     */
    fun saveVideo(context: Context, source: File, displayName: String): SavedMedia {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the video in the gallery")
            try {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Could not write the video to the gallery")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            // The gallery has its own copy now.
            source.delete()
            return SavedMedia(uri, null, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
        }

        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: return SavedMedia(null, source, source.absolutePath)
        val target = File(moviesDir, ALBUM).apply { mkdirs() }.let { File(it, displayName) }
        source.copyTo(target, overwrite = true)
        source.delete()
        return SavedMedia(null, target, target.absolutePath)
    }

    /** Makes [name] safe to use as a file name. */
    fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "video" }.take(80)
}
