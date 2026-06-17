package com.example.wifidirectmesh.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

object ImageCompressor {
    fun compressUri(context: Context, uri: Uri): ByteArray? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val originalBytes = inputStream.use { it.readBytes() }
            val originalSize = originalBytes.size
            
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
            }
            val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, options) ?: return null
            
            // First pass: max 800x800, quality 40
            var scaledBitmap = resizeBitmap(bitmap, 800, 800)
            var bos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 40, bos)
            var compressedBytes = bos.toByteArray()
            
            // Second pass: if > 500KB, max 600x600, quality 25
            if (compressedBytes.size > 500 * 1024) {
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
                scaledBitmap = resizeBitmap(bitmap, 600, 600)
                bos = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 25, bos)
                compressedBytes = bos.toByteArray()
            }
            
            // Subsequent passes if still > 500KB
            var currentQuality = 20
            var currentSize = 500
            while (compressedBytes.size > 500 * 1024 && currentQuality > 5) {
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
                scaledBitmap = resizeBitmap(bitmap, currentSize, currentSize)
                bos = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, bos)
                compressedBytes = bos.toByteArray()
                currentQuality -= 5
                currentSize -= 100
            }
            
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()
            
            Log.d("ImageCompressor", "Image compressed from ${formatSize(originalSize.toLong())} to ${formatSize(compressedBytes.size.toLong())}")
            compressedBytes
        } catch (e: Exception) {
            Log.e("ImageCompressor", "Error compressing image: ${e.message}", e)
            null
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxW && height <= maxH) return bitmap
        
        val ratio = width.toFloat() / height.toFloat()
        var targetW = maxW
        var targetH = maxH
        if (width > height) {
            targetH = (maxW / ratio).toInt()
        } else {
            targetW = (maxH * ratio).toInt()
        }
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    fun formatSize(bytes: Long): String {
        return if (bytes >= 1024 * 1024) {
            String.format(Locale.US, "%.1fMB", bytes.toFloat() / (1024 * 1024))
        } else {
            String.format(Locale.US, "%.0fKB", bytes.toFloat() / 1024)
        }
    }
}
