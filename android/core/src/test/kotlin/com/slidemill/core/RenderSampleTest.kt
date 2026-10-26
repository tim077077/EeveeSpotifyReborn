package com.slidemill.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders sample slides to core/build/samples for a visual check. */
class RenderSampleTest {
    @Test fun render_samples() {
        val e = TestKit.engine()
        val (bg, _, _) = e.images.storeBytes(TestKit.jpeg(800, 1000, java.awt.Color(120, 90, 140)), "library/test")
        val spec = Models.SPEC.of(obj(
            "overlay_hook" to true, "background_opacity" to 40,
            "slides" to arr(
                obj("role" to "hook", "image" to bg, "text_items" to arr(obj("text" to "5 habits that quietly fixed my sleep", "font_size" to "large"))),
                obj("text_items" to arr(
                    obj("text" to "1. Dark room", "font" to "bebas", "text_style" to "background", "text_color" to "yellow"),
                    obj("text" to "Blackout curtains changed everything for me", "font" to "cormorant_italic", "font_size" to "small", "text_style" to "light_background", "text_color" to "pink"),
                )),
                obj("image" to bg, "text_items" to arr(obj("text" to "left aligned text", "text_align" to "left", "text_color" to "black", "text_width" to "50%"))),
            ),
        ))
        val out = File("build/samples").absoluteFile
        val rels = e.renderer.renderSlideshow(spec, File(e.mediaDir, "samples"))
        out.mkdirs()
        rels.forEach { File(e.mediaDir, it).copyTo(File(out, File(it).name), overwrite = true) }
        assertTrue(File(out, "slide_01.jpg").length() > 10_000)
    }
}
