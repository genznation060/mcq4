package com.example.encoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Base64
import android.util.Log
import com.example.util.StorageHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeVideoEncoder(private val context: Context) {
    companion object {
        private const val TAG = "NativeVideoEncoder"
        private const val MIME_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MIME_AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TIMEOUT_USEC = 10_000L
    }

    private var videoCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var tempOutputFile: File? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private var width = 1920
    private var height = 1080
    private var fps = 30
    private var chosenColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private var displayName = "mcq-video.mp4"

    // Reusable buffers
    private var argbBuffer: IntArray? = null
    private var yuvBuffer: ByteArray? = null

    // Audio storage
    private var pendingAudioFormat: MediaFormat? = null
    private val pendingAudioPackets = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()

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
            this.width = width
            this.height = height
            this.fps = fps
            this.displayName = filename
            this.videoTrackIndex = -1
            this.audioTrackIndex = -1
            this.muxerStarted = false
            this.pendingAudioPackets.clear()
            this.pendingAudioFormat = null

            argbBuffer = IntArray(width * height)
            yuvBuffer = ByteArray(width * height * 3 / 2)

            tempOutputFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.mp4")
            if (tempOutputFile?.exists() == true) tempOutputFile?.delete()

            mediaMuxer = MediaMuxer(tempOutputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Step 1: Process Audio if present
            if (!base64AudioWav.isNullOrEmpty()) {
                encodeWavToAac(base64AudioWav)
            }

            // Step 2: Configure Video Codec
            val codecInfo = selectCodec(MIME_VIDEO)
            val caps = codecInfo?.getCapabilitiesForType(MIME_VIDEO)
            chosenColorFormat = chooseColorFormat(caps)

            val videoFormat = MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, chosenColorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            videoCodec = MediaCodec.createEncoderByType(MIME_VIDEO).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            Log.i(TAG, "NativeVideoEncoder started successfully: ${width}x${height} @ ${fps}fps ($chosenColorFormat)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NativeVideoEncoder", e)
            release()
            false
        }
    }

    private fun encodeWavToAac(base64Wav: String) {
        try {
            val wavBytes = Base64.decode(base64Wav, Base64.DEFAULT)
            if (wavBytes.size < 44) return

            // Parse WAV header
            val headerBuffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
            val channels = headerBuffer.getShort(22).toInt()
            val sampleRate = headerBuffer.getInt(24)
            val bitDepth = headerBuffer.getShort(34).toInt()

            if (bitDepth != 16 || channels < 1 || sampleRate < 8000) {
                Log.w(TAG, "Unsupported WAV format: channels=$channels, rate=$sampleRate, depth=$bitDepth")
                return
            }

            // Find data chunk
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

            val pcmDataLength = wavBytes.size - dataOffset
            if (pcmDataLength <= 0) return

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
                    Log.i(TAG, "Audio format configured: $pendingAudioFormat")
                } else if (outIdx >= 0) {
                    val outBuf = audioCodec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)) {
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
            Log.i(TAG, "Offline audio pre-encoded: ${pendingAudioPackets.size} AAC packets")
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding audio track", e)
        }
    }

    fun encodeFrame(frameIndex: Int, base64Jpeg: String): Boolean {
        val codec = videoCodec ?: return false
        val muxer = mediaMuxer ?: return false

        try {
            val jpegBytes = Base64.decode(base64Jpeg, Base64.DEFAULT)
            var bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return false

            if (bmp.width != width || bmp.height != height) {
                bmp = Bitmap.createScaledBitmap(bmp, width, height, true)
            }

            val argb = argbBuffer ?: IntArray(width * height).also { argbBuffer = it }
            val yuv = yuvBuffer ?: ByteArray(width * height * 3 / 2).also { yuvBuffer = it }

            bmp.getPixels(argb, 0, width, 0, 0, width, height)

            if (chosenColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                encodeYUV420P(yuv, argb, width, height)
            } else {
                encodeYUV420SP(yuv, argb, width, height)
            }

            var inputEnqueued = false
            while (!inputEnqueued) {
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
            }

            drainVideoEncoder(false)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding frame $frameIndex", e)
            return false
        }
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        val codec = videoCodec ?: return
        val muxer = mediaMuxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    Log.w(TAG, "Video format changed after muxer started")
                } else {
                    val newFormat = codec.outputFormat
                    videoTrackIndex = muxer.addTrack(newFormat)
                    Log.i(TAG, "Video track added index=$videoTrackIndex, format=$newFormat")

                    if (pendingAudioFormat != null) {
                        audioTrackIndex = muxer.addTrack(pendingAudioFormat!!)
                        Log.i(TAG, "Audio track added index=$audioTrackIndex, format=$pendingAudioFormat")
                    }

                    muxer.start()
                    muxerStarted = true
                    Log.i(TAG, "MediaMuxer started")

                    // Drain pending audio packets into muxer
                    if (audioTrackIndex >= 0) {
                        for (packet in pendingAudioPackets) {
                            val byteBuffer = ByteBuffer.wrap(packet.first)
                            muxer.writeSampleData(audioTrackIndex, byteBuffer, packet.second)
                        }
                        pendingAudioPackets.clear()
                        Log.i(TAG, "All pre-encoded audio written to muxer")
                    }
                }
            } else if (outputIndex >= 0) {
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

    fun finish(): String {
        return try {
            val codec = videoCodec
            if (codec != null) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_USEC)
                if (inIdx >= 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainVideoEncoder(true)
            }

            release()

            val tempFile = tempOutputFile
            if (tempFile != null && tempFile.exists()) {
                val sizeMb = String.format("%.2f", tempFile.length().toDouble() / (1024 * 1024))
                val savedPath = StorageHelper.saveVideoToMediaStore(context, tempFile, displayName)
                tempFile.delete()
                Log.i(TAG, "Video export complete: $savedPath ($sizeMb MB)")
                "{\"success\":true,\"path\":\"$savedPath\",\"filename\":\"$displayName\",\"sizeMb\":\"$sizeMb\"}"
            } else {
                "{\"success\":false,\"error\":\"Video file empty or not generated\"}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing video export", e)
            release()
            "{\"success\":false,\"error\":\"${e.message}\"}"
        }
    }

    private fun release() {
        try {
            videoCodec?.stop()
        } catch (e: Exception) {}
        try {
            videoCodec?.release()
        } catch (e: Exception) {}
        videoCodec = null

        try {
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
        } catch (e: Exception) {}
        try {
            mediaMuxer?.release()
        } catch (e: Exception) {}
        mediaMuxer = null
        muxerStarted = false
    }

    private fun selectCodec(mimeType: String): MediaCodecInfo? {
        val numCodecs = MediaCodecList.getCodecCount()
        for (i in 0 until numCodecs) {
            val codecInfo = MediaCodecList.getCodecInfoAt(i)
            if (!codecInfo.isEncoder) continue
            val types = codecInfo.supportedTypes
            for (type in types) {
                if (type.equals(mimeType, ignoreCase = true)) {
                    return codecInfo
                }
            }
        }
        return null
    }

    private fun chooseColorFormat(caps: MediaCodecInfo.CodecCapabilities?): Int {
        if (caps == null) return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        for (colorFormat in caps.colorFormats) {
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                return colorFormat
            }
        }
        for (colorFormat in caps.colorFormats) {
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                return colorFormat
            }
        }
        return caps.colorFormats.firstOrNull() ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var index = 0

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[index]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128

                yuv420sp[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()

                if (j % 2 == 0 && i % 2 == 0 && uvIndex < yuv420sp.size - 1) {
                    yuv420sp[uvIndex++] = (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                    yuv420sp[uvIndex++] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                }
                index++
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
                val pixel = argb[index]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128

                yuv420p[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()

                if (j % 2 == 0 && i % 2 == 0 && uIndex < frameSize + frameSize / 4 && vIndex < yuv420p.size) {
                    yuv420p[uIndex++] = (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                    yuv420p[vIndex++] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                }
                index++
            }
        }
    }
}
