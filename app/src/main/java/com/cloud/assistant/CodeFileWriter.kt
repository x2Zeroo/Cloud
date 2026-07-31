package com.cloud.assistant

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object CodeFileWriter {

    data class SaveResult(val displayPath: String, val viewUri: Uri)

    fun save(context: Context, filename: String, content: String): SaveResult? {
        val safeName = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "cloud_code.txt" }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                SaveResult("Downloads/$safeName", uri)
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, safeName)
                FileOutputStream(file).use { it.write(content.toByteArray(Charsets.UTF_8)) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                SaveResult(file.absolutePath, uri)
            }
        } catch (e: Exception) {
            null
        }
    }
}
