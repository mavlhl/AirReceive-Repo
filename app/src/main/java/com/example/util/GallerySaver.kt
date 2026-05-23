package com.example.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException

object GallerySaver {
    fun saveToPublicGallery(context: Context, file: File, fileName: String, mimeType: String): Boolean {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AirReceive")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return false

        return try {
            resolver.openOutputStream(imageUri).use { outputStream ->
                if (outputStream == null) throw IOException("Failed to open output stream")
                FileInputStream(file).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                resolver.delete(imageUri, null, null)
            } catch (ignored: Exception) {}
            false
        }
    }
}
