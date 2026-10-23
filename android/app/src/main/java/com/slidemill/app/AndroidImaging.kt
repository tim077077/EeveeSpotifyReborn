package com.slidemill.app

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.ExifInterface
import com.slidemill.core.Canvas2D
import com.slidemill.core.FontRef
import com.slidemill.core.Imaging
import com.slidemill.core.NormalizedImage
import com.slidemill.core.Picture
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The slide renderer's drawing primitives on Android's Canvas, with the bundled fonts. */
class AndroidImaging(private val assets: AssetManager) : Imaging {
    private class Pic(val bitmap: Bitmap) : Picture {
        override val width get() = bitmap.width
        override val height get() = bitmap.height
    }

    private class F(val paint: Paint) : FontRef {
        private val fm = paint.fontMetrics
        override val ascent = -fm.ascent
        override val descent = fm.descent
        override fun measure(text: String): Float = paint.measureText(text)
    }

    private val typefaces = HashMap<String, Typeface>()

    private fun typeface(file: String, weight: Int?): Typeface = synchronized(typefaces) {
        typefaces.getOrPut("$file|$weight") {
            runCatching {
                Typeface.Builder(assets, "fonts/$file").apply {
                    if (weight != null) setFontVariationSettings("'wght' $weight")
                }.build()
            }.getOrNull() ?: Typeface.DEFAULT_BOLD
        }
    }

    /** Decode with EXIF rotation applied, downsampling anything far larger than `maxSide`. */
    private fun decode(data: ByteArray, maxSide: Int = 3000): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0) throw IllegalArgumentException("cannot identify image file")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: throw IllegalArgumentException("cannot identify image file")
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    override fun normalize(data: ByteArray, maxW: Int, maxH: Int, quality: Int): NormalizedImage {
        val src = decode(data)
        val scale = minOf(1.0, maxW.toDouble() / src.width, maxH.toDouble() / src.height)
        val out = if (scale < 1.0) {
            Bitmap.createScaledBitmap(src, maxOf(1, (src.width * scale).toInt()), maxOf(1, (src.height * scale).toInt()), true)
        } else src
        // JPEG has no alpha: flatten transparent PNGs onto white.
        val flat = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(out, 0f, 0f, null) }
        val result = NormalizedImage(jpeg(flat, quality), flat.width, flat.height)
        flat.recycle()
        if (out !== src) out.recycle()
        src.recycle()
        return result
    }

    override fun load(data: ByteArray): Picture = Pic(decode(data))

    override fun font(file: String, weight: Int?, sizePx: Int, cjk: Boolean): FontRef {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            // CJK text uses the system font (Android falls back to Noto CJK per glyph).
            typeface = if (cjk) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else typeface(file, weight)
            textSize = sizePx.toFloat()
        }
        return F(paint)
    }

    override fun canvas(width: Int, height: Int): Canvas2D = object : Canvas2D {
        val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        private fun coverSrc(src: Bitmap, w: Int, h: Int, centerY: Double): Rect {
            val scale = maxOf(w.toDouble() / src.width, h.toDouble() / src.height)
            val sw = w / scale
            val sh = h / scale
            val sx = (src.width - sw) / 2
            val sy = ((src.height - sh) * centerY).coerceIn(0.0, src.height - sh)
            return Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
        }

        override fun drawCover(picture: Picture, x: Int, y: Int, w: Int, h: Int, centerY: Double) {
            val src = (picture as Pic).bitmap
            canvas.drawBitmap(src, coverSrc(src, w, h, centerY), Rect(x, y, x + w, y + h), filter)
        }

        override fun drawBlurredCover(picture: Picture) {
            // Downscale hard, then upscale twice with filtering: a cheap, smooth blur.
            val src = (picture as Pic).bitmap
            val tiny = Bitmap.createBitmap(maxOf(1, width / 32), maxOf(1, height / 32), Bitmap.Config.ARGB_8888)
            Canvas(tiny).drawBitmap(src, coverSrc(src, tiny.width, tiny.height, 0.45), Rect(0, 0, tiny.width, tiny.height), filter)
            val mid = Bitmap.createScaledBitmap(tiny, maxOf(1, width / 8), maxOf(1, height / 8), true)
            canvas.drawBitmap(mid, null, Rect(0, 0, width, height), filter)
            tiny.recycle()
            mid.recycle()
        }

        override fun drawContain(picture: Picture) {
            val src = (picture as Pic).bitmap
            val scale = minOf(width.toFloat() / src.width, height.toFloat() / src.height)
            val w = src.width * scale
            val h = src.height * scale
            canvas.drawBitmap(src, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), filter)
        }

        override fun verticalGradient(topRgb: Int, bottomRgb: Int) {
            val p = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, height.toFloat(), topRgb or 0xFF000000.toInt(), bottomRgb or 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        }

        override fun fill(argb: Int) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = argb })
        }

        override fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, argb: Int) {
            canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb })
        }

        override fun text(text: String, x: Float, top: Float, font: FontRef, fillRgb: Int, stroke: Float, strokeRgb: Int) {
            val f = font as F
            val baseline = top + f.ascent
            if (stroke > 0) {
                val p = Paint(f.paint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke * 2
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                    color = strokeRgb or 0xFF000000.toInt()
                }
                canvas.drawText(text, x, baseline, p)
            }
            canvas.drawText(text, x, baseline, Paint(f.paint).apply { style = Paint.Style.FILL; color = fillRgb or 0xFF000000.toInt() })
        }

        override fun jpeg(quality: Int): ByteArray {
            val out = jpeg(bitmap, quality)
            bitmap.recycle()
            return out
        }
    }
}
