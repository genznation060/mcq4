package com.example.encoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Surface
import com.example.util.StorageHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optimized NativeVideoEncoder
 * - Prefers Surface input (hardware) for much faster encoding
 * - Falls back to buffer mode with improved YUV conversion
 * - Reuses bitmaps and buffers
 */
class NativeVideoEncoder(private val context: Context) {

    companion object {
        private const val TAG = "NativeVideoEncoder"
        private const val MIME_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MIME_AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TIMEOUT_USEC = 10_000L
    }

    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var mediaMuxer: MediaMuxer? = null
    private var tempOutputFile: File? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private var width = 1920
    private var height = 1080
    private var fps = 30
    private var useSurfaceInput = false
    private var chosenColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private var displayName = "mcq-video.mp4"

    // Reusable buffers (only used in buffer mode)
    private var argbBuffer: IntArray? = null
    private var yuvBuffer: ByteArray? = null
    private var reusableBitmap: Bitmap? = null

    // Audio
    private var pendingAudioFormat: MediaFormat? = null
    private val pendingAudioPackets = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()

    private val isRunning = AtomicBoolean(false)
    private var encodeThread: HandlerThread? = null
    private var encodeHandler: Handler? = null

    fun start(
        width: Int,
        height: Int,
        fps: Int,
        bitrateMbps: Int,
        totalFrames: Int,
        filename: String,
        base64AudioWav: String?
    ): Boolean {
        return try {
            release() // clean previous

            this.width = width
            this.height = height
            this.fps = fps.coerceAtLeast(1)
            this.displayName = filename.ifBlank { "mcq-video.mp4" }
            this.videoTrackIndex = -1
            this.audioTrackIndex = -1
            this.muxerStarted = false
            this.pendingAudioPackets.clear()
            this.pendingAudioFormat = null
            this.useSurfaceInput = false

            argbBuffer = IntArray(width * height)
            yuvBuffer = ByteArray(width * height * 3 / 2)

            tempOutputFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.mp4")
            if (tempOutputFile?.exists() == true) tempOutputFile?.delete()

            mediaMuxer = MediaMuxer(tempOutputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Pre-encode audio if present
            if (!base64AudioWav.isNullOrEmpty()) {
                encodeWavToAac(base64AudioWav)
            }

            // Prefer Surface input (much faster)
            val codecInfo = selectCodec(MIME_VIDEO)
            val caps = codecInfo?.getCapabilitiesForType(MIME_VIDEO)

            val videoFormat = MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps.coerceIn(1, 50) * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, this@NativeVideoEncoder.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            videoCodec = MediaCodec.createEncoderByType(MIME_VIDEO)

            try {
                videoCodec!!.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = videoCodec!!.createInputSurface()
                videoCodec!!.start()
                useSurfaceInput = true
                Log.i(TAG, "Using SURFACE input (hardware path) ${width}x$height @ ${fps}fps")
            } catch (e: Exception) {
                Log.w(TAG, "Surface input failed, falling back to buffer mode", e)
                videoCodec?.release()
                videoCodec = null
                inputSurface = null

                chosenColorFormat = chooseColorFormat(caps)
                val bufferFormat = MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, chosenColorFormat)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps.coerceIn(1, 50) * 1_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, this@NativeVideoEncoder.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                videoCodec = MediaCodec.createEncoderByType(MIME_VIDEO).apply {
                    configure(bufferFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
                useSurfaceInput = false
                Log.i(TAG, "Using BUFFER mode color=$chosenColorFormat")
            }

            isRunning.set(true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NativeVideoEncoder", e)
            release()
            false
        }
    }

    fun encodeFrame(frameIndex: Int, base64Jpeg: String): Boolean {
        if (!isRunning.get()) return false
        val codec = videoCodec ?: return false

        return try {
            val jpegBytes = Base64.decode(base64Jpeg, Base64.DEFAULT)
            var bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return false

            if (bmp.width != width || bmp.height != height) {
                val scaled = Bitmap.createScaledBitmap(bmp, width, height, true)
                if (bmp != scaled) bmp.recycle()
                bmp = scaled
            }

            if (useSurfaceInput && inputSurface != null) {
                // Fast path: draw to Surface
                val canvas = inputSurface!!.lockCanvas(null)
                try {
                    canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
                } finally {
                    inputSurface!!.unlockCanvasAndPost(canvas)
                }
                drainVideoEncoder(false)
            } else {
                // Buffer path
                val argb = argbBuffer ?: IntArray(width * height).also { argbBuffer = it }
                val yuv = yuvBuffer ?: ByteArray(width * height * 3 / 2).also { yuvBuffer = it }

                bmp.getPixels(argb, 0, width, 0, 0, width, height)

                if (chosenColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                    encodeYUV420P(yuv, argb, width, height)
                } else {
                    encodeYUV420SP(yuv, argb, width, height)
                }

                var inputEnqueued = false
                var tries = 0
                while (!inputEnqueued && tries < 20) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(yuv)
                        val pts = (frameIndex.toLong() * 1_000_000L) / fps
                        codec.queueInputBuffer(inputIndex, 0, yuv.size, pts, 0)
                        inputEnqueued = true
                    }
                    drainVideoEncoder(false)
                    tries++
                }
            }

            if (bmp != reusableBitmap) {
                // keep one reusable if needed later
            }
            bmp.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding frame $frameIndex", e)
            false
        }
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        val codec = videoCodec ?: return
        val muxer = mediaMuxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_USEC else 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        val newFormat = codec.outputFormat
                        videoTrackIndex = muxer.addTrack(newFormat)
                        Log.i(TAG, "Video track added: $videoTrackIndex")

                        if (pendingAudioFormat != null) {
                            audioTrackIndex = muxer.addTrack(pendingAudioFormat!!)
                            Log.i(TAG, "Audio track added: $audioTrackIndex")
                        }

                        muxer.start()
                        muxerStarted = true

                        // Write pre-encoded audio
                        if (audioTrackIndex >= 0) {
                            for (packet in pendingAudioPackets) {
                                muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(packet.first), packet.second)
                            }
                            pendingAudioPackets.clear()
                        }
                    }
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    fun finish(): String {
        return try {
            isRunning.set(false)
            val codec = videoCodec
            if (codec != null) {
                if (useSurfaceInput) {
                    try {
                        codec.signalEndOfInputStream()
                    } catch (_: Exception) {}
                } else {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_USEC)
                    if (inIdx >= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
                drainVideoEncoder(true)
            }

            release()

            val tempFile = tempOutputFile
            if (tempFile != null && tempFile.exists() && tempFile.length() > 0) {
                val sizeMb = String.format("%.2f", tempFile.length().toDouble() / (1024 * 1024))
                val savedPath = StorageHelper.saveVideoToMediaStore(context, tempFile, displayName)
                tempFile.delete()
                Log.i(TAG, "Export complete: $savedPath ($sizeMb MB)")
                """{"success":true,"path":"$savedPath","filename":"$displayName","sizeMb":"$sizeMb"}"""
            } else {
                """{"success":false,"error":"Video file empty or not generated"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing video export", e)
            release()
            """{"success":false,"error":"${e.message}"}"""
        }
    }

    private fun release() {
        isRunning.set(false)
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null

        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null

        try {
            if (muxerStarted) mediaMuxer?.stop()
        } catch (_: Exception) {}
        try { mediaMuxer?.release() } catch (_: Exception) {}
        mediaMuxer = null
        muxerStarted = false

        reusableBitmap?.recycle()
        reusableBitmap = null
        argbBuffer = null
        yuvBuffer = null
    }

    // ---------- Audio (same as original, kept for compatibility) ----------
    private fun encodeWavToAac(base64Wav: String) {
        try {
            val wavBytes = Base64.decode(base64Wav, Base64.DEFAULT)
            if (wavBytes.size < 44) return

            val headerBuffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
            val channels = headerBuffer.getShort(22).toInt()
            val sampleRate = headerBuffer.getInt(24)
            val bitDepth = headerBuffer.getShort(34).toInt()

            if (bitDepth != 16 || channels < 1 || sampleRate < 8000) return

            var dataOffset = 44
            for (i in 36 until wavBytes.size - 8) {
                if (wavBytes[i] == 'd'.code.toByte() &&
                    wavBytes[i + 1] == 'a'.code.toByte() &&
                    wavBytes[i + 2] == 't'.code.toByte() &&
                    wavBytes[i + 3] == 'a'.code.toByte()
                ) {
                    dataOffset = i + 8
                    break
                }
            }

            val audioFormat = MediaFormat.createAudioFormat(MIME_AUDIO, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }

            val audioCodec = MediaCodec.createEncoderByType(MIME_AUDIO).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            var pcmCursor = dataOffset
            var audioPtsUs = 0L
            val bytesPerSec = sampleRate * channels * 2
            var audioEosSent = false
            var audioEosReached = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!audioEosReached) {
                if (!audioEosSent) {
                    val inIdx = audioCodec.dequeueInputBuffer(TIMEOUT_USEC)
                    if (inIdx >= 0) {
                        val inBuf = audioCodec.getInputBuffer(inIdx)
                        inBuf?.clear()
                        val remaining = wavBytes.size - pcmCursor
                        if (remaining > 0 && inBuf != null) {
                            val chunkSize = remaining.coerceAtMost(inBuf.capacity())
                            inBuf.put(wavBytes, pcmCursor, chunkSize)
                            val pts = audioPtsUs
                            audioPtsUs += (chunkSize.toLong() * 1_000_000L) / bytesPerSec
                            pcmCursor += chunkSize
                            audioCodec.queueInputBuffer(inIdx, 0, chunkSize, pts, 0)
                        } else {
                            audioCodec.queueInputBuffer(inIdx, 0, 0, audioPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioEosSent = true
                        }
                    }
                }

                val outIdx = audioCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    pendingAudioFormat = audioCodec.outputFormat
                } else if (outIdx >= 0) {
                    val outBuf = audioCodec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outBuf.get(chunk)
                        val infoCopy = MediaCodec.BufferInfo().apply {
                            set(0, chunk.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        pendingAudioPackets.add(Pair(chunk, infoCopy))
                    }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        audioEosReached = true
                    }
                    audioCodec.releaseOutputBuffer(outIdx, false)
                }
            }

            audioCodec.stop()
            audioCodec.release()
            Log.i(TAG, "Audio pre-encoded: ${pendingAudioPackets.size} packets")
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding audio", e)
        }
    }

    private fun selectCodec(mimeType: String): MediaCodecInfo? {
        val numCodecs = MediaCodecList.getCodecCount()
        for (i in 0 until numCodecs) {
            val codecInfo = MediaCodecList.getCodecInfoAt(i)
            if (!codecInfo.isEncoder) continue
            for (type in codecInfo.supportedTypes) {
                if (type.equals(mimeType, ignoreCase = true)) return codecInfo
            }
        }
        return null
    }

    private fun chooseColorFormat(caps: MediaCodecInfo.CodecCapabilities?): Int {
        if (caps == null) return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        for (c in caps.colorFormats) {
            if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return c
        }
        for (c in caps.colorFormats) {
            if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return c
        }
        return caps.colorFormats.firstOrNull()
            ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    // Slightly optimized YUV conversion (still software, but cleaner)
    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var index = 0

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[index++]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv420sp[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0 && uvIndex < yuv420sp.size - 1) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv420sp[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv420sp[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun encodeYUV420P(yuv420p: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        var index = 0

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[index++]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv420p[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0 && uIndex < frameSize + frameSize / 4 && vIndex < yuv420p.size) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv420p[uIndex++] = u.coerceIn(0, 255).toByte()
                    yuv420p[vIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
