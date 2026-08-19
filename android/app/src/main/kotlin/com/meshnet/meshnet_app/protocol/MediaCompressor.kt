package com.meshnet.meshnet_app.protocol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

class MediaCompressor(private val context: Context) {

    companion object {
        const val MAX_IMAGE_DIMENSION = 1920
        const val JPEG_QUALITY = 75
    }

    fun compressImage(uri: Uri): ByteArray {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI")

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        val width = options.outWidth
        val height = options.outHeight
        var sampleSize = 1
        while (width / sampleSize > MAX_IMAGE_DIMENSION || height / sampleSize > MAX_IMAGE_DIMENSION) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val stream2 = context.contentResolver.openInputStream(uri)!!
        val bitmap = BitmapFactory.decodeStream(stream2, null, decodeOptions)
        stream2.close()

        if (bitmap == null) throw IllegalArgumentException("Failed to decode image")

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        bitmap.recycle()
        return outputStream.toByteArray()
    }

    fun readFile(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI")
        return stream.use { it.readBytes() }
    }

    fun getFileSize(uri: Uri): Long {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE))
        } ?: 0L
    }

    fun getMimeType(uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "application/octet-stream"
    }

    fun getFileName(uri: Uri): String {
        var name = "unknown"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }
}
