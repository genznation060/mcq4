package com.example.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object StorageHelper {
    private const val TAG = "StorageHelper"

    fun saveVideoToMediaStore(context: Context, sourceFile: File, displayName: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MCQStudio")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    "Movies/MCQStudio/$displayName"
                } else {
                    fallbackSave(sourceFile, Environment.DIRECTORY_MOVIES, displayName)
                }
            } else {
                fallbackSave(sourceFile, Environment.DIRECTORY_MOVIES, displayName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video to MediaStore", e)
            sourceFile.absolutePath
        }
    }

    fun saveImageToMediaStore(context: Context, data: ByteArray, displayName: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MCQStudio")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    "Pictures/MCQStudio/$displayName"
                } else {
                    fallbackWrite(data, Environment.DIRECTORY_PICTURES, displayName)
                }
            } else {
                fallbackWrite(data, Environment.DIRECTORY_PICTURES, displayName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving image to MediaStore", e)
            "Pictures/$displayName"
        }
    }

    fun saveDownloadToMediaStore(context: Context, data: ByteArray, displayName: String, mimeType: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MCQStudio")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    "Download/MCQStudio/$displayName"
                } else {
                    fallbackWrite(data, Environment.DIRECTORY_DOWNLOADS, displayName)
                }
            } else {
                fallbackWrite(data, Environment.DIRECTORY_DOWNLOADS, displayName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving download", e)
            "Download/$displayName"
        }
    }

    private fun fallbackSave(sourceFile: File, dirType: String, displayName: String): String {
        val root = Environment.getExternalStoragePublicDirectory(dirType)
        val studioDir = File(root, "MCQStudio").apply { mkdirs() }
        val target = File(studioDir, displayName)
        sourceFile.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    private fun fallbackWrite(data: ByteArray, dirType: String, displayName: String): String {
        val root = Environment.getExternalStoragePublicDirectory(dirType)
        val studioDir = File(root, "MCQStudio").apply { mkdirs() }
        val target = File(studioDir, displayName)
        FileOutputStream(target).use { it.write(data) }
        return target.absolutePath
    }
}
