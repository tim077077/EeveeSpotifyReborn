package com.slidemill.core

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** The engine's platform on a desktop JVM: Java2D drawing, fonts from the repo, no encryption key store. */
class JvmPlatform(
    override val name: String = "android",
    private val repoRoot: File = findRepoRoot(),
    private val envMap: Map<String, String> = emptyMap(),
) : Platform {
    /** What the "TikTok page" lists for a profile (tests fill it). */
    val profilePosts = mutableMapOf<String, List<Obj>>()

    override fun env(name: String): String? = envMap[name]
    override fun encryptSecret(plain: String): String = "b64:" + Base64.getEncoder().encodeToString(plain.toByteArray())
    override fun decryptSecret(stored: String): String = String(Base64.getDecoder().decode(stored.removePrefix("b64:")))
    override val imaging: Imaging = Java2DImaging(File(repoRoot, "fonts"))

    /** Which account slot each TikTok read went through. */
    val via = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun listProfilePosts(username: String, max: Int, via: String, known: Set<String>): List<Obj> {
        this.via += via
        return profilePosts[username].orEmpty().take(max)
    }

    /** What "TikTok search" returns per query (tests fill it). */
    val searchResults = mutableMapOf<String, List<Obj>>()
    @Volatile var searchDelayMs = 0L

    override fun searchPosts(query: String, max: Int, via: String): List<Obj> {
        this.via += via
        Thread.sleep(searchDelayMs)
        return searchResults[query].orEmpty().take(max)
    }
    override fun defaultTimezone(): String = "Europe/Bucharest"
    override fun log(message: String) {}

    companion object {
        fun findRepoRoot(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                if (File(dir, "extension/tiktok-inject.js").isFile && File(dir, "fonts").isDirectory) return dir
                dir = dir.parentFile
            }
            error("repo root not found")
        }
    }
}

class Java2DImaging(private val fontsDir: File) : Imaging {
    private class Pic(val img: BufferedImage) : Picture {
        override val width get() = img.width
        override val height get() = img.height
    }

    private class F(val font: Font) : FontRef {
        private val frc = FontRenderContext(AffineTransform(), true, true)
        private val lm = font.getLineMetrics("Hg", frc)
        override val ascent = lm.ascent
        override val descent = lm.descent
        override fun measure(text: String): Float = if (text.isEmpty()) 0f else font.getStringBounds(text, frc).width.toFloat()
    }

    private fun decode(data: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(data)) ?: throw IllegalArgumentException("cannot identify image file")

    private fun rgb(src: BufferedImage, w: Int = src.width, h: Int = src.height): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private fun jpeg(img: BufferedImage, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality / 100f }
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    override fun normalize(data: ByteArray, maxW: Int, maxH: Int, quality: Int): NormalizedImage {
        val img = decode(data)
        val scale = minOf(1.0, maxW.toDouble() / img.width, maxH.toDouble() / img.height)
        val out = rgb(img, maxOf(1, (img.width * scale).toInt()), maxOf(1, (img.height * scale).toInt()))
        return NormalizedImage(jpeg(out, quality), out.width, out.height)
    }

    override fun load(data: ByteArray): Picture = Pic(rgb(decode(data)))

    override fun font(file: String, weight: Int?, sizePx: Int, cjk: Boolean): FontRef {
        val base = File(fontsDir, file).takeIf { it.isFile }?.let { Font.createFont(Font.TRUETYPE_FONT, it) } ?: Font(Font.SANS_SERIF, Font.BOLD, sizePx)
        return F(base.deriveFont(sizePx.toFloat()))
    }

    override fun canvas(width: Int, height: Int): Canvas2D = object : Canvas2D {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        }

        override fun drawCover(picture: Picture, x: Int, y: Int, w: Int, h: Int, centerY: Double) {
            val src = (picture as Pic).img
            val scale = maxOf(w.toDouble() / src.width, h.toDouble() / src.height)
            val sw = w / scale
            val sh = h / scale
            val sx = (src.width - sw) / 2
            val sy = ((src.height - sh) * centerY).coerceIn(0.0, src.height - sh)
            g.drawImage(src, x, y, x + w, y + h, sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt(), null)
        }

        override fun drawBlurredCover(picture: Picture) {
            val small = BufferedImage(maxOf(1, width / 16), maxOf(1, height / 16), BufferedImage.TYPE_INT_RGB)
            val sg = small.createGraphics()
            val src = (picture as Pic).img
            val scale = maxOf(small.width.toDouble() / src.width, small.height.toDouble() / src.height)
            sg.drawImage(src, ((small.width - src.width * scale) / 2).toInt(), ((small.height - src.height * scale) / 2).toInt(),
                (src.width * scale).toInt(), (src.height * scale).toInt(), null)
            sg.dispose()
            val k = FloatArray(9) { 1f / 9 }
            val blurred = ConvolveOp(Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null).filter(small, null)
            g.drawImage(blurred, 0, 0, width, height, null)
        }

        override fun drawContain(picture: Picture) {
            val src = (picture as Pic).img
            val scale = minOf(width.toDouble() / src.width, height.toDouble() / src.height)
            val w = (src.width * scale).toInt()
            val h = (src.height * scale).toInt()
            g.drawImage(src, (width - w) / 2, (height - h) / 2, w, h, null)
        }

        override fun verticalGradient(topRgb: Int, bottomRgb: Int) {
            g.paint = GradientPaint(0f, 0f, Color(topRgb), 0f, height.toFloat(), Color(bottomRgb))
            g.fillRect(0, 0, width, height)
        }

        override fun fill(argb: Int) {
            g.composite = AlphaComposite.SrcOver
            g.color = Color(argb, true)
            g.fillRect(0, 0, width, height)
        }

        override fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, argb: Int) {
            g.color = Color(argb, true)
            g.fill(RoundRectangle2D.Float(left, top, right - left, bottom - top, radius * 2, radius * 2))
        }

        override fun text(text: String, x: Float, top: Float, font: FontRef, fillRgb: Int, stroke: Float, strokeRgb: Int) {
            if (text.isEmpty()) return
            val f = (font as F).font
            val layout = TextLayout(text, f, g.fontRenderContext)
            val shape = layout.getOutline(AffineTransform.getTranslateInstance(x.toDouble(), (top + font.ascent).toDouble()))
            if (stroke > 0) {
                g.color = Color(strokeRgb)
                g.stroke = BasicStroke(stroke * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(shape)
            }
            g.color = Color(fillRgb)
            g.fill(shape)
        }

        override fun jpeg(quality: Int): ByteArray = jpeg(img, quality)
    }
}
