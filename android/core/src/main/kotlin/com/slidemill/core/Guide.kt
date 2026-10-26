package com.slidemill.core

/** What Claude reads (writing_guide) before writing slideshows: what holds attention on TikTok. */
object Guide {
    val WRITING = """You write viral TikTok photo-carousel slideshows (the "slideshow" post type).

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
}
