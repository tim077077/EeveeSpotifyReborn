package com.slidemill.core

/** Slide writer and Studio format planner (port of app/generator.py; prompts are kept verbatim). */
class Generator(private val llm: Llm) {
    companion object {
        val SYSTEM_PROMPT = """You write viral TikTok photo-carousel slideshows (the "slideshow" post type).

What works on TikTok slideshows:
- Slide 1 is the hook: one punchy line that creates curiosity or names a payoff ("5 habits that quietly fixed my sleep", "things I wish I knew before 25"). It must make people swipe.
- Body slides are a listicle: one idea per slide, a short bold heading plus at most one supporting line. Concrete, specific, a little surprising. Numbered when the hook promises a number, and the count must match.
- Write like a real person posting from their own account, not a brand. No corporate tone, no "In this slideshow".
- Keep on-slide text short: headings under ~8 words, supporting lines under ~20 words. Never put hashtags on slides.
- If a product is given, mention it once, naturally, as one of the list items or a soft final tip (a "soft CTA") - never a hard sell, never a URL on a slide. Mark that slide role "cta" only if it is a dedicated final slide.
- If no product is given, do not invent one, and do not add a CTA slide.

Styling defaults (override whenever the creative direction says otherwise):
- hook: font "tiktok", font_size "large", text_style "outline", text_color "white".
- body heading: font "tiktok", font_size "medium", text_style "outline"; supporting line font_size "small".
- Use "background"/"light_background" text styles when the direction asks for boxed or highlighted text.
- Default text_position "center" unless the direction says top or bottom.

Backgrounds: each slide gets a photo behind the text. image_query is a short stock-photo search; image_prompt describes the same photo for an AI image generator. Keep every slide's background in one consistent visual style (same palette, lighting and photographic feel), leave calm space where the text sits, and never ask for text, letters or logos inside the image."""

        val CAMPAIGN_PROMPT = """You plan TikTok slideshow content from a short brief written by a creator.

Turn the brief into a reusable format:
- name: short name for this content series.
- format_summary: one line describing the slideshow format.
- style: creative direction for the slide writer - slide count, voice, list structure, fonts and text placement. Pick what performs on TikTok for this niche if the brief doesn't say.
- visual_style: the look of every background photo (subject matter, palette, lighting, camera feel), consistent across the series, suitable for both stock search and AI image generation. No text in images.
- aspect_ratio: "4:5" unless the brief asks otherwise.
- hooks: distinct, scroll-stopping first-slide hooks for separate slideshows in this series. Vary the angle (numbers, mistakes, before/after, "things nobody tells you", POV). No two may be near-duplicates.
- language: the language the slides should be written in (the brief's language unless it says otherwise)."""

        fun fingerprint(text: String): String = text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

        fun tooSimilar(candidate: String, previous: List<String>, threshold: Double = 0.86): Boolean {
            val fp = fingerprint(candidate)
            return previous.any { it.isNotEmpty() && ratio(fp, it) >= threshold }
        }

        /** difflib.SequenceMatcher(None, a, b).ratio() (no junk heuristics; inputs are short). */
        fun ratio(a: String, b: String): Double {
            val total = a.length + b.length
            if (total == 0) return 1.0
            return 2.0 * matches(a, 0, a.length, b, 0, b.length) / total
        }

        private fun matches(a: String, alo: Int, ahi: Int, b: String, blo: Int, bhi: Int): Int {
            // Longest common block, earliest in a then b (as difflib's find_longest_match).
            var bestI = alo; var bestJ = blo; var bestK = 0
            // j2len[j - blo + 1] = length of the common run ending at a[i], b[j]
            var j2len = IntArray(bhi - blo + 1)
            for (i in alo until ahi) {
                val next = IntArray(bhi - blo + 1)
                for (j in blo until bhi) {
                    if (a[i] == b[j]) {
                        val k = j2len[j - blo] + 1
                        next[j - blo + 1] = k
                        if (k > bestK) { bestI = i - k + 1; bestJ = j - k + 1; bestK = k }
                    }
                }
                j2len = next
            }
            if (bestK == 0) return 0
            return bestK + matches(a, alo, bestI, b, blo, bestJ) + matches(a, bestI + bestK, ahi, b, bestJ + bestK, bhi)
        }

        fun buildPrompt(
            topic: String,
            style: String = "",
            language: String = "English",
            product: Map<String, Any?>? = null,
            recentTitles: List<String>? = null,
            slideCountHint: Int? = null,
            visualStyle: String = "",
            reference: String = "",
        ): String {
            val parts = mutableListOf("<topic>\n$topic\n</topic>")
            if (style.isNotEmpty()) parts += "<creative_direction>\n$style\n</creative_direction>"
            if (visualStyle.isNotEmpty()) parts += "<visual_style>\n$visualStyle\n</visual_style>"
            if (reference.isNotEmpty()) {
                parts += "<reference_slideshow>\nA slideshow that performed well. Copy its format, pacing and tone, but write entirely " +
                    "new text (never reuse its sentences):\n$reference\n</reference_slideshow>"
            }
            if (product != null) {
                parts += "<product>\nname: ${product.str("name")}\ndescription: ${product.str("description")}\n</product>"
            }
            if (slideCountHint != null && slideCountHint > 0) parts += "Make exactly $slideCountHint slides including the hook."
            if (!recentTitles.isNullOrEmpty()) {
                val listed = recentTitles.take(30).joinToString("\n") { "- $it" }
                parts += "These slideshows were already made for this account. Make this one clearly different " +
                    "(new angle, new hook wording, new list items):\n$listed"
            }
            parts += "Write all on-slide text, the caption and the description in $language."
            return parts.joinToString("\n\n")
        }
    }

    fun generatePlan(
        topic: String,
        style: String = "",
        language: String = "English",
        product: Map<String, Any?>? = null,
        recentTitles: List<String>? = null,
        slideCountHint: Int? = null,
        visualStyle: String = "",
        reference: String = "",
    ): Obj {
        var prompt = buildPrompt(topic, style, language, product, recentTitles, slideCountHint, visualStyle, reference)
        val recentFps = recentTitles.orEmpty().map { fingerprint(it) }
        var plan: Obj? = null
        for (attempt in 0 until 2) {
            plan = try {
                llm.completeJson(SYSTEM_PROMPT, prompt, Shapes.SLIDESHOW_PLAN)
            } catch (e: LlmError) {
                throw GenerationError(e.message ?: "AI error")
            }
            val slides = plan.objs("slides")
            if (slides.isEmpty()) throw GenerationError("The model returned no slides.")
            val hookText = slides[0].objs("text_items").joinToString(" ") { it.str("text") }
            if (attempt == 0 && tooSimilar(hookText, recentFps)) {
                prompt += "\n\nYour previous draft hook \"$hookText\" is too close to an existing post. Use a completely different hook and list."
                continue
            }
            break
        }
        val out = plan!!
        out["caption"] = out.str("caption").take(89)
        return out
    }

    fun planCampaign(brief: String, hookCount: Int): Obj {
        val prompt = "<brief>\n$brief\n</brief>\n\nWrite exactly $hookCount hooks."
        val campaign = try {
            llm.completeJson(CAMPAIGN_PROMPT, prompt, Shapes.CAMPAIGN_PLAN)
        } catch (e: LlmError) {
            throw GenerationError(e.message ?: "AI error")
        }
        if (campaign.strings("hooks").isEmpty()) throw GenerationError("The model returned no hooks.")
        return campaign
    }
}

class GenerationError(message: String) : RuntimeException(message)
