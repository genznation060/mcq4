package com.example.encoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.example.util.StorageHelper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media3 Transformer based encoder.
 * Collects frames as temporary images, then composes them into a video.
 * Much more reliable and usually faster than pure MediaCodec + JPEG bridge.
 */
class Media3VideoEncoder(private val context: Context) {

    companion object {
        private const val TAG = "Media3VideoEncoder"
    }

    private var width = 1920
    private var height = 1080
    private var fps = 30
    private var displayName = "mcq-video.mp4"
    private var totalFrames = 0

    private val frameFiles = mutableListOf<File>()
    private var tempDir: File? = null
    private val isRunning = AtomicBoolean(false)

    fun start(
        width: Int,
        height: Int,
        fps: Int,
        bitrateMbps: Int,   // kept for API compatibility (Transformer uses its own bitrate)
        totalFrames: Int,
        filename: String,
        base64AudioWav: String?
    ): Boolean {
        return try {
            release()

            this.width = width
            this.height = height
            this.fps = fps.coerceAtLeast(1)
            this.displayName = filename.ifBlank { "mcq-video.mp4" }
            this.totalFrames = totalFrames

            tempDir = File(context.cacheDir, "mcq_frames_${System.currentTimeMillis()}").apply {
                mkdirs()
            }
            frameFiles.clear()
            isRunning.set(true)

            Log.i(TAG, "Media3 encoder started: ${width}x$height @ ${fps}fps, frames=$totalFrames")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Media3VideoEncoder", e)
            release()
            false
        }
    }

    fun encodeFrame(frameIndex: Int, base64Jpeg: String): Boolean {
        if (!isRunning.get()) return false
        val dir = tempDir ?: return false

        return try {
            val jpegBytes = Base64.decode(base64Jpeg, Base64.DEFAULT)
            val file = File(dir, "frame_%06d.jpg".format(frameIndex))
            FileOutputStream(file).use { it.write(jpegBytes) }
            frameFiles.add(file)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving frame $frameIndex", e)
            false
        }
    }

    fun finish(): String {
        isRunning.set(false)

        return try {
            if (frameFiles.isEmpty()) {
                release()
                return """{"success":false,"error":"No frames collected"}"""
            }

            val outputFile = File(context.cacheDir, "mcq_out_${System.currentTimeMillis()}.mp4")
            if (outputFile.exists()) outputFile.delete()

            val latch = CountDownLatch(1)
            var resultPath: String? = null
            var errorMsg: String? = null

            val editedItems = frameFiles.map { file ->
                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                EditedMediaItem.Builder(mediaItem)
                    .setDurationUs((1_000_000L / fps))   // duration of each frame
                    .setFrameRate(fps)
                    .build()
            }

            val sequence = EditedMediaItemSequence(editedItems)
            val composition = Composition.Builder(sequence).build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        resultPath = outputFile.absolutePath
                        latch.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        errorMsg = exception.message
                        Log.e(TAG, "Transformer error", exception)
                        latch.countDown()
                    }
                })
                .build()

            Log.i(TAG, "Starting Media3 Transformer with ${frameFiles.size} frames...")
            transformer.start(composition, outputFile.absolutePath)

            // Wait max 3 minutes
            val finished = latch.await(3, TimeUnit.MINUTES)

            if (!finished) {
                transformer.cancel()
                release()
                return """{"success":false,"error":"Export timeout"}"""
            }

            if (errorMsg != null) {
                release()
                return """{"success":false,"error":"$errorMsg"}"""
            }

            val finalFile = File(resultPath ?: outputFile.absolutePath)
            if (!finalFile.exists() || finalFile.length() == 0L) {
                release()
                return """{"success":false,"error":"Output file empty"}"""
            }

            val sizeMb = String.format("%.2f", finalFile.length().toDouble() / (1024 * 1024))
            val savedPath = StorageHelper.saveVideoToMediaStore(context, finalFile, displayName)
            finalFile.delete()

            release()
            Log.i(TAG, "Media3 export complete: $savedPath ($sizeMb MB)")
            """{"success":true,"path":"$savedPath","filename":"$displayName","sizeMb":"$sizeMb"}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing Media3 export", e)
            release()
            """{"success":false,"error":"${e.message}"}"""
        }
    }

    private fun release() {
        isRunning.set(false)
        frameFiles.forEach { it.delete() }
        frameFiles.clear()
        tempDir?.deleteRecursively()
        tempDir = null
    }
}
