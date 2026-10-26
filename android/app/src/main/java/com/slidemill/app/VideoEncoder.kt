package com.slidemill.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Slides -> H.264 MP4 with the phone's hardware encoder (what ffmpeg does on the server):
 * each slide is shown for `secondsPerSlide`, letterboxed to the first slide's size.
 */
object VideoEncoder {
    private const val FPS = 30
    private const val TIMEOUT_US = 10_000L

    fun encode(frames: List<File>, secondsPerSlide: Double, target: File) {
        require(frames.isNotEmpty()) { "no slides" }
        val first = BitmapFactory.decodeFile(frames[0].path) ?: error("can't read ${frames[0].name}")
        // Encoders want even (often 16-aligned) sizes.
        val w = first.width / 16 * 16
        val h = first.height / 16 * 16
        first.recycle()
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        val perSlide = maxOf(1, Math.round(secondsPerSlide * FPS).toInt())
        val total = perSlide * frames.size
        var frameIndex = 0
        var inputDone = false
        var currentSlide = -1
        var yuv: Yuv? = null

        try {
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (frameIndex >= total) {
                            codec.queueInputBuffer(inIndex, 0, 0, pts(frameIndex), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val slide = frameIndex / perSlide
                            if (slide != currentSlide) {
                                yuv = Yuv.of(frames[slide], w, h)
                                currentSlide = slide
                            }
                            val image = codec.getInputImage(inIndex) ?: error("encoder gave no input image")
                            yuv!!.writeTo(image)
                            codec.queueInputBuffer(inIndex, 0, w * h * 3 / 2, pts(frameIndex), 0)
                            frameIndex++
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun pts(frame: Int): Long = frame * 1_000_000L / FPS

    /** One slide converted to YUV 4:2:0 once, copied into each encoder input image. */
    private class Yuv(val w: Int, val h: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
        fun writeTo(image: android.media.Image) {
            val planes = image.planes
            copyPlane(planes[0], y, w, h)
            copyPlane(planes[1], u, w / 2, h / 2)
            copyPlane(planes[2], v, w / 2, h / 2)
        }

        private fun copyPlane(plane: android.media.Image.Plane, src: ByteArray, pw: Int, ph: Int) {
            val buf = plane.buffer
            val row = plane.rowStride
            val pix = plane.pixelStride
            for (r in 0 until ph) {
                val base = r * row
                if (pix == 1) {
                    buf.position(base)
                    buf.put(src, r * pw, pw)
                } else {
                    for (c in 0 until pw) {
                        val idx = base + c * pix
                        if (idx < buf.limit()) buf.put(idx, src[r * pw + c])
                    }
                }
            }
        }

        companion object {
            fun of(file: File, w: Int, h: Int): Yuv {
                val src = BitmapFactory.decodeFile(file.path) ?: error("can't read ${file.name}")
                val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(frame)
                canvas.drawColor(Color.BLACK)
                val scale = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
                val dw = src.width * scale
                val dh = src.height * scale
                canvas.drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                src.recycle()
                val argb = IntArray(w * h)
                frame.getPixels(argb, 0, w, 0, 0, w, h)
                frame.recycle()
                val y = ByteArray(w * h)
                val u = ByteArray(w * h / 4)
                val v = ByteArray(w * h / 4)
                for (j in 0 until h) for (i in 0 until w) {
                    val p = argb[j * w + i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    y[j * w + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                    if (j % 2 == 0 && i % 2 == 0) {
                        val k = (j / 2) * (w / 2) + i / 2
                        u[k] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                        v[k] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                    }
                }
                return Yuv(w, h, y, u, v)
            }
        }
    }
}
