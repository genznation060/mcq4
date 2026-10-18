package com.example.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.example.encoder.NativeVideoEncoder
import com.example.util.StorageHelper

class AndroidBridge(
    private val context: Context,
    private val onToastRequested: (String) -> Unit
) {
    companion object {
        private const val TAG = "AndroidBridge"
    }

    private val nativeEncoder = NativeVideoEncoder(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun initNativeEncoder(
        width: Int,
        height: Int,
        fps: Int,
        bitrateMbps: Int,
        totalFrames: Int,
        filename: String,
        base64AudioWav: String?
    ): Boolean {
        Log.i(TAG, "initNativeEncoder called: ${width}x$height, ${fps}fps, $bitrateMbps Mbps, frames=$totalFrames, file=$filename")
        return nativeEncoder.start(width, height, fps, bitrateMbps, totalFrames, filename, base64AudioWav)
    }

    @JavascriptInterface
    fun encodeFrame(frameIndex: Int, base64Jpeg: String): Boolean {
        return nativeEncoder.encodeFrame(frameIndex, base64Jpeg)
    }

    @JavascriptInterface
    fun finishNativeEncoder(): String {
        Log.i(TAG, "finishNativeEncoder called")
        val result = nativeEncoder.finish()
        mainHandler.post {
            onToastRequested("Video export saved to Movies/MCQStudio")
        }
        return result
    }

    @JavascriptInterface
    fun saveBlobToStorage(base64Data: String, filename: String, mimeType: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val lower = filename.lowercase()
            val savedLocation: String = when {
                lower.endsWith(".mp4") || lower.endsWith(".webm") -> {
                    // Temporary file then save to media store
                    val temp = java.io.File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
                    temp.writeBytes(bytes)
                    val loc = StorageHelper.saveVideoToMediaStore(context, temp, filename)
                    temp.delete()
                    loc
                }
                lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> {
                    StorageHelper.saveImageToMediaStore(context, bytes, filename)
                }
                else -> {
                    StorageHelper.saveDownloadToMediaStore(context, bytes, filename, mimeType)
                }
            }

            mainHandler.post {
                val msg = "Saved: $savedLocation"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                onToastRequested(msg)
            }
            Log.i(TAG, "Blob saved successfully to $savedLocation")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving blob to storage", e)
            mainHandler.post {
                Toast.makeText(context, "Error saving $filename: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
