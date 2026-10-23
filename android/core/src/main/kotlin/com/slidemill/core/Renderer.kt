package com.slidemill.core

import java.io.File
import java.security.MessageDigest

/**
 * Slide layout (port of app/renderer.py): background image(s) + overlay + TikTok-style text.
 * Measuring and drawing go through the platform's [Imaging], so the phone and the tests share it.
 */
class Renderer(private val imaging: Imaging, private val mediaDir: File) {
    companion object {
        val CANVAS = mapOf("4:5" to (1080 to 1350), "9:16" to (1080 to 1920), "1:1" to (1080 to 1080), "16:9" to (1920 to 1080))

        // (file, variable-font weight or null)
        val FONTS = mapOf(
            "tiktok" to ("Montserrat[wght].ttf" to 800),
            "bebas" to ("BebasNeue-Regular.ttf" to null),
            "anton" to ("Anton-Regular.ttf" to null),
            "archivo" to ("ArchivoBlack-Regular.ttf" to null),
            "cormorant" to ("CormorantGaramond[wght].ttf" to 500),
            "cormorant_italic" to ("CormorantGaramond-Italic[wght].ttf" to 500),
            "playfair_italic" to ("PlayfairDisplay-Italic[wght].ttf" to 500),
        )

        // Pixel sizes at 1080px canvas width.
        val SIZES = mapOf("extra_extra_small" to 34, "extra_small" to 42, "small" to 52, "medium" to 64, "large" to 80, "extra_large" to 100)

        // Condensed display faces read small at the same pixel size.
        val FONT_SCALE = mapOf("bebas" to 1.3, "anton" to 1.1, "cormorant" to 1.15, "cormorant_italic" to 1.15, "playfair_italic" to 1.05)

        val COLORS = mapOf(
            "white" to 0xFFFFFF, "black" to 0x000000, "yellow" to 0xFFDD00, "red" to 0xEE1D52,
            "pink" to 0xFF69B4, "orange" to 0xFF8C00, "green" to 0x25D366, "blue" to 0x2078FF, "purple" to 0x8C52FF,
        )
        val LIGHT_COLORS = setOf("white", "yellow", "green", "pink", "orange")
        val CJK_RE = Regex("[぀-ヿ㐀-鿿가-힯฀-๿]")
        val WIDTH_FRACTION = mapOf("full" to 0.94, "100%" to 0.88, "80%" to 0.76, "50%" to 0.5)

        val GRID_CELLS = mapOf(
            "single" to listOf(doubleArrayOf(0.0, 0.0, 1.0, 1.0)),
            "1:2" to listOf(doubleArrayOf(0.0, 0.0, 1.0, 0.5), doubleArrayOf(0.0, 0.5, 1.0, 1.0)),
            "1:3" to listOf(doubleArrayOf(0.0, 0.0, 1.0, 1 / 3.0), doubleArrayOf(0.0, 1 / 3.0, 1.0, 2 / 3.0), doubleArrayOf(0.0, 2 / 3.0, 1.0, 1.0)),
            "2:1" to listOf(doubleArrayOf(0.0, 0.0, 0.5, 1.0), doubleArrayOf(0.5, 0.0, 1.0, 1.0)),
            "2:2" to listOf(
                doubleArrayOf(0.0, 0.0, 0.5, 0.5), doubleArrayOf(0.5, 0.0, 1.0, 0.5),
                doubleArrayOf(0.0, 0.5, 0.5, 1.0), doubleArrayOf(0.5, 0.5, 1.0, 1.0),
            ),
        )

        fun px(item: Map<String, Any?>, canvasW: Int): Int {
            val size = item.str("font_size", "medium")
            val base = if (size.endsWith("px") && size.dropLast(2).all { it.isDigit() } && size.length > 2) {
                size.dropLast(2).toInt()
            } else SIZES[size] ?: SIZES.getValue("medium")
            return maxOf(12, Math.round(base * (FONT_SCALE[item.str("font")] ?: 1.0) * canvasW / 1080).toInt())
        }

        /** Python's round(): halves go to the even neighbour. */
        fun pyRound(x: Double): Int = Math.rint(x).toInt()
    }

    private val fontCache = HashMap<String, FontRef>()

    private fun font(name: String, size: Int, cjk: Boolean): FontRef = synchronized(fontCache) {
        val (file, weight) = FONTS[name] ?: FONTS.getValue("tiktok")
        fontCache.getOrPut("$file|$weight|$size|$cjk") { imaging.font(file, weight, size, cjk) }
    }

    /** Load a media-relative path or an http(s) URL (downloads are cached on disk). */
    fun loadBytes(ref: String): ByteArray {
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            val hash = MessageDigest.getInstance("SHA-256").digest(ref.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
            val cache = File(mediaDir, "cache/$hash.img")
            if (!cache.isFile) {
                cache.parentFile.mkdirs()
                val resp = Net.get(ref, timeoutSec = 30)
                if (resp.status >= 400) throw IllegalStateException("image download failed (${resp.status}): $ref")
                cache.writeBytes(resp.body)
            }
            return cache.readBytes()
        }
        val path = File(mediaDir, ref).canonicalFile
        if (!path.path.startsWith(mediaDir.canonicalPath + File.separator)) throw IllegalArgumentException("image path escapes media dir: $ref")
        return path.readBytes()
    }

    private fun background(canvas: Canvas2D, slide: Map<String, Any?>, w: Int, h: Int, keepOriginal: Boolean) {
        val refs = slide.strings("images").ifEmpty { listOfNotNull(slide.strOrNull("image")) }
        if (refs.isEmpty()) {
            canvas.verticalGradient(0x242430, 0x0A0A0E)
            return
        }
        val cells = GRID_CELLS[slide.str("image_layout", "single")] ?: GRID_CELLS.getValue("single")
        if (refs.size == 1 || cells.size == 1) {
            val pic = imaging.load(loadBytes(refs[0]))
            if (keepOriginal) {
                canvas.drawBlurredCover(pic)
                canvas.drawContain(pic)
            } else {
                canvas.drawCover(pic, 0, 0, w, h)
            }
            return
        }
        cells.forEachIndexed { i, c ->
            val x0 = pyRound(c[0] * w); val y0 = pyRound(c[1] * h); val x1 = pyRound(c[2] * w); val y1 = pyRound(c[3] * h)
            canvas.drawCover(imaging.load(loadBytes(refs[i % refs.size])), x0, y0, x1 - x0, y1 - y0)
        }
    }

    private fun wrap(text: String, font: FontRef, maxW: Int): List<String> {
        val lines = mutableListOf<String>()
        for (para in text.split("\n")) {
            // CJK text has no spaces; wrap per character there.
            val (tokens, joiner) = if (CJK_RE.containsMatchIn(para) && ' ' !in para) {
                para.map { it.toString() } to ""
            } else para.split(" ") to " "
            var current = ""
            for (tok in tokens) {
                val trial = if (current.isNotEmpty()) "$current$joiner$tok" else tok
                if (font.measure(trial) <= maxW || current.isEmpty()) current = trial
                else {
                    lines += current
                    current = tok
                }
            }
            lines += current
        }
        return lines
    }

    /** One text item laid out: its size, and how to draw it at a given top-left. */
    private inner class TextBlock(val item: Map<String, Any?>, canvasW: Int) {
        val size = px(item, canvasW)
        val font = font(item.str("font", "tiktok"), size, CJK_RE.containsMatchIn(item.str("text")))
        val lines = wrap(item.str("text").trim(), font, pyRound(canvasW * (WIDTH_FRACTION[item.str("text_width", "80%")] ?: 0.76)))
        val lineH = Math.round(font.ascent + font.descent)
        val gap = pyRound(size * 0.12)
        val style = item.str("text_style", "outline")
        val stroke = if (style == "outline") maxOf(2, pyRound(size * 0.09)) else 0
        val boxed = "background" in style
        val padX = if (boxed) pyRound(size * 0.35) else 0
        val padY = if (boxed) pyRound(size * 0.14) else 0
        val widths = lines.map { Math.round(font.measure(it)) }
        val width = (widths.maxOrNull() ?: 0) + 2 * (padX + stroke)
        val height = lines.size * (lineH + 2 * padY) + (lines.size - 1) * gap + 2 * stroke

        fun draw(canvas: Canvas2D, left: Int, top: Int) {
            val colorName = item.str("text_color", "white")
            val color = COLORS[colorName] ?: 0xFFFFFF
            var boxFill: Int? = null
            val fill: Int
            when (style) {
                "background" -> {
                    boxFill = 0xFF000000.toInt() or color
                    fill = if (colorName in LIGHT_COLORS) 0x000000 else 0xFFFFFF
                }
                "light_background" -> {
                    boxFill = (225 shl 24) or 0xFFFFFF
                    fill = if (colorName == "white") 0x000000 else color
                }
                else -> fill = color
            }
            val strokeColor = if (colorName == "black") 0xFFFFFF else 0x000000
            val align = item.str("text_align", "center")
            var y = stroke
            lines.forEachIndexed { i, line ->
                val lw = widths[i]
                val x = when (align) {
                    "left" -> stroke + padX
                    "right" -> width - stroke - padX - lw
                    else -> (width - lw) / 2
                }
                boxFill?.let {
                    canvas.roundRect(
                        (left + x - padX).toFloat(), (top + y).toFloat(), (left + x + lw + padX).toFloat(),
                        (top + y + lineH + 2 * padY).toFloat(), pyRound(size * 0.22).toFloat(), it,
                    )
                }
                canvas.text(line, (left + x).toFloat(), (top + y + padY).toFloat(), font, fill, stroke.toFloat(), strokeColor)
                y += lineH + 2 * padY + gap
            }
        }
    }

    fun renderSlide(slide: Map<String, Any?>, spec: Map<String, Any?>): ByteArray {
        val (w, h) = CANVAS[spec.str("aspect_ratio", "4:5")] ?: CANVAS.getValue("4:5")
        val canvas = imaging.canvas(w, h)
        background(canvas, slide, w, h, spec.bool("keep_original_aspect_ratio"))

        val overlayOn = when (slide.str("role", "body")) {
            "hook" -> spec.bool("overlay_hook")
            "cta" -> spec.bool("overlay_cta")
            else -> spec.bool("overlay_body")
        }
        val opacity = spec.int("background_opacity", 20)
        if (overlayOn && opacity > 0) canvas.fill(pyRound(255 * opacity / 100.0) shl 24)

        val items = slide.objs("text_items").filter { it.str("text").isNotBlank() }
        val blocks = items.map { TextBlock(it, w) }
        if (blocks.isNotEmpty()) {
            val gap = pyRound(h * 0.025)
            val total = blocks.sumOf { it.height } + gap * (blocks.size - 1)
            val position = slide.strOrNull("text_position") ?: spec.str("text_position", "center")
            val margin = pyRound(h * 0.12)
            var y = when (position) {
                "top" -> margin
                "bottom" -> h - margin - total
                else -> Math.floorDiv(h - total, 2)
            }
            y = maxOf(pyRound(h * 0.04), y)
            for (block in blocks) {
                val side = pyRound(w * 0.06)
                val x = when (block.item.str("text_align", "center")) {
                    "left" -> side
                    "right" -> w - side - block.width
                    else -> Math.floorDiv(w - block.width, 2)
                }
                block.draw(canvas, maxOf(0, x), y)
                y += block.height + gap
            }
        }
        return canvas.jpeg(92)
    }

    /** Render every slide to JPEG under `outDir`; returns media-relative paths. */
    fun renderSlideshow(spec: Map<String, Any?>, outDir: File): List<String> {
        outDir.mkdirs()
        return spec.objs("slides").mapIndexed { i, slide ->
            val target = File(outDir, "slide_%02d.jpg".format(i + 1))
            target.writeBytes(renderSlide(slide, spec))
            target.relativeTo(mediaDir).path.replace(File.separatorChar, '/')
        }
    }
}
