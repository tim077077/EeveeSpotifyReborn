# How reel.farm works (teardown)

Sources: their public landing page, their public API docs (`reel.farm/api-docs`), and their public changelog (`reel.farm/changes`). Nothing here comes from logging into their product or decompiling it. Items marked **inferred** are my reading of the evidence, not something they state.

## The product in one line

You set up an "automation" once: a TikTok account, a list of hook ideas, a style prompt, an image source and posting times. On every scheduled run it writes a new listicle slideshow with an LLM, puts text on images, and pushes it to TikTok, either as a draft or as a public post.

## Pipeline (what happens on each run)

1. **Cron fires.** Schedules are cron expressions in Pacific time, with several per automation. A run can also be started by hand (`/automations/{id}/run`, with mode `export` or `draft_only`).
2. **Pick a hook.** `slideshow_hooks` is a list of topic templates that get rotated, with a random pick unless one is given. The changelog says "narrative" was renamed to "hooks".
3. **LLM writes the slides.** The Enterprise plan says "add an Anthropic API key for unlimited generations", so they run on Claude. **Inferred:** a single structured call that returns slide text plus styling, guided by the style prompt. It handles tone, slide count, fonts, sizes and text position "through prompts" (changelog 2026‑01‑28). Product automations get a "soft CTA" (changelog 2026‑03‑06).
4. **Uniqueness check.** The changelog mentions "content uniqueness checking" and "deduplication for promo content". **Inferred:** they compare against earlier posts from the same automation.
5. **Pick images.** They draw from per-user image collections and do automatic Pinterest search (`auto_pull_images`, `/pinterest/search`). Collections can be set separately for the first slide, the body and the CTA slides. They avoid repeats until a collection is used up ("slide image exhaustion" fix). There is an `auto_images_no_text` filter.
6. **Render.** The changelog says "Pillow-based slideshow rendering engine with overlay opacity, rounded corners" (2026‑02‑27). The spec covers:
   - aspect ratios 4:5 (default), 9:16, 1:1, 16:9, plus "keep original aspect ratio"
   - grid layouts: single, 1:2, 1:3, 2:1, 2:2
   - text items with font, size (xxs–xl or px), colour (about 28), style (outline, plain, solid background, light background), width, alignment and anchoring
   - a darkening overlay toggled per slide type (hook, body, CTA) with an opacity value
   - CJK and Thai fonts
   - optional MP4 export with seconds per slide
7. **Publish.** They use TikTok's Content Posting API as a photo carousel with `PULL_FROM_URL`:
   - `DIRECT_POST` publishes straight away. There is a cap of 6 per account per 24 hours.
   - `MEDIA_UPLOAD` sends the post to the creator's TikTok inbox as a draft and has no cap.
   - The caption is limited to 89 characters and the description to 4,000. `auto_music` only applies to direct posts.
8. **Track.** Each post's status is polled. Views, likes, comments, shares and bookmarks are pulled per post and also added up per account.

## Other features

- **Unlimited accounts and team members.** You connect accounts through TikTok OAuth. They suggest logging out of tiktok.com in between, so the next connection picks a different account. Connection links can be shared.
- **Slideshow library.** A searchable database of real TikTok slideshow accounts, filterable by niche and region. It's for inspiration. **Inferred:** they built it by scraping TikTok.
- **AI UGC selfies and "hook + demo" video format.** A separate product line that makes AI selfie images and videos.
- **Public REST API, plus a downloadable Claude Code skill.** It has a 20 requests/min rate limit and allows at most 3 generations running at once.
- **Credits.** Each plan gets a monthly allowance; automations pause when credits hit 0.
- **Pricing.** Plans are $49, $95 and $195 a month, for 100, 250 and 750 slideshows.

## Where Slidemill matches it and where it differs

| reel.farm | Slidemill |
|---|---|
| LLM (Anthropic) writes slides | Claude via structured output (`SlideshowPlan`), with a server-side refusal fallback |
| Pillow renderer, overlays, grids, fonts, CJK | Same approach (`app/renderer.py`), using open-licence fonts |
| Pinterest auto-images | Pexels / Unsplash official APIs, plus uploaded collections. Pinterest has no public search API, so pulling from it means scraping and breaks Pinterest's terms. |
| Cron automations, hook rotation, pause | Same (`app/scheduler.py`, `pipeline.pick_hook`) |
| Uniqueness check | Earlier titles go into the prompt, and a fuzzy hook match triggers a retry |
| Direct post / draft, 6/day cap | Same, with automatic fallback to draft when the cap is hit |
| Analytics | Publish-status polling plus the Display API `video/query` metrics |
| MP4 export | ffmpeg |
| REST API | `/api/v1/*` with similar shapes |
| Slideshow library (scraped) | Not built. It needs a TikTok scraping operation. |
| AI UGC selfies/videos | Not built. It's a separate product, and an image/video-generation provider could be plugged in. |
| Credits / billing / teams | Not built. This is a self-hosted, single-workspace tool. |
