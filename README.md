# Slidemill

A self-hosted TikTok slideshow factory. Describe what you want and it plans the format, writes every slide, makes or finds the background images, and renders finished slideshows. It can post them to TikTok on a schedule, as drafts or as public posts, and it tracks how each post does. It works with any AI key: Claude, OpenAI, Gemini, OpenRouter, Groq, DeepSeek, xAI, Mistral, Together, a local Ollama, or any OpenAI-compatible server.

It was built by tearing down how reel.farm works; see [docs/REELFARM_TEARDOWN.md](docs/REELFARM_TEARDOWN.md).

## Features

- **Studio.** Type something like "glow-up slideshows for girls 16–22, soft pink aesthetic, 6 slides". It plans a content format (voice, slide structure, visual style and a set of hooks), then makes N complete slideshows with text and AI-generated or stock backgrounds. One checkbox turns the format into an automation that keeps posting.
- **Slideshow library.** Scrape public TikTok slideshow accounts and posts into your own database. You get followers, link in bio, niche (AI-classified), what the account promotes, and per-post views, likes and saves. Slide images are stored locally. You can search by keyword and filter by niche. "Read slides" extracts the on-image text with a vision model, and "Remix" makes new slideshows in a winning post's format on your own topic.
- **Any AI key.** Paste a key in Settings and the provider is detected from its prefix. The model and base URL can be overridden. Claude uses structured outputs. Everything else goes through the OpenAI-compatible API in JSON mode, with schema validation and one automatic repair retry.
- **AI backgrounds.** Images come from OpenAI (`gpt-image-1`), Gemini (`gemini-2.5-flash-image`), Together (FLUX) or Pollinations (free, no key, watermarked). An OpenAI or Gemini writing key makes images too, so one key covers everything.
- **AI slideshow generator.** You give it a prompt; the AI returns hook, body and CTA slides with styling, a caption and a description. Recent titles go back into the prompt so new slideshows don't repeat old ones.
- **Best times to post, by country.** Pick the country you want to reach and how many posts a day. You get a weekly schedule in that country's local time, with a day × hour heatmap. Multi-timezone countries (US, Canada, Brazil, Australia, Mexico, Indonesia) are weighted by population, and local weekends (Fri–Sat in Saudi Arabia, Egypt and Israel) and late-evening cultures (Spain, Latin America, the Gulf) are respected. The schedule starts from an engagement baseline and adapts as real data comes in: scraped library posts from accounts in that country and your own posts' views. One click turns on **auto-posting**: each slideshow is generated 15 minutes early and published on time, plus 0–4 random minutes, either publicly or to drafts.
- **Slide renderer.** TikTok-style outline text and boxed text, 7 fonts, 4 aspect ratios, grid layouts, darkening overlays and Korean/Japanese/Chinese text. Every slide can be edited in the dashboard and re-rendered.
- **Automations.** Each automation has a list of hooks that it rotates through, a style prompt, a language, an optional product for a soft CTA, image settings and posting times (cron). Automations can be paused, resumed or run now.
- **Images.** Upload your own image collections or import them from Pexels/Unsplash. You can also let it search stock photos for each slide automatically. Collections are cycled least-used first so images don't repeat.
- **Browser poster (no developer app).** The Chrome extension in `extension/` posts Slidemill's queue from your logged-in tiktok.com tab, using the same internal endpoints TikTok Studio's web uploader uses. Pick "Post from my browser" anywhere you choose how to post: one slideshow, an automation, Best times or Studio. Slidemill caps posts per day (6 by default) and spaces them out (20 min by default), and view counts come from each post's public page. "Save drafts from my browser" is a placeholder: those jobs wait in the queue, never posted publicly, until the "Save draft" request is captured. Download the extension in **Settings → Browser poster** and read `extension/HOW-TO-USE.txt`. This route is against TikTok's terms, so use an account you can afford to lose.
- **Android app.** `android/` holds the browser poster as a phone app: the dashboard, a TikTok tab where you log in once, and a background poster service that runs the same `tiktok-inject.js`. The APK is built by the "Android APK" GitHub workflow. See [android/README.md](android/README.md).
- **TikTok posting.** Connect any number of accounts with OAuth. Posts go out as photo carousels, either straight to your TikTok drafts (`MEDIA_UPLOAD`) or published directly (`DIRECT_POST`). The direct-post daily limit is enforced, with automatic fallback to drafts.
- **Analytics.** It polls each post's publish status and syncs views, likes, comments and shares every 10 minutes.
- **MP4 export** for Reels and Shorts, plus a **REST API** (`/api/v1`, docs at `/docs`).

## Deploy (production)

On a fresh Ubuntu server with a domain pointed at it:

```bash
git clone <your repo URL> slidemill && cd slidemill && sudo ./deploy/install.sh
```

The script sets up Docker, generates the dashboard password and the encryption key, and starts Slidemill behind Caddy with automatic HTTPS. **Follow [docs/LAUNCH_CHECKLIST.md](docs/LAUNCH_CHECKLIST.md)** for the server, the keys, the TikTok app review and your first two weeks.

**Security**

- The server refuses to start on a public URL without a strong `SLIDEMILL_API_KEY`.
- API keys and TikTok tokens are encrypted at rest.
- The container runs as a non-root user, and the app sends standard security headers.
- Direct posts follow TikTok's content-sharing rules: the creator is shown, privacy has no default, toggles are off by default, commercial content is disclosed, and music-usage consent is required. Scheduled direct posts happen only after you give consent once in the automation's settings; without it they go to drafts.

## Run it locally

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env        # fill in keys
.venv/bin/uvicorn app.main:app --port 8000
# open http://localhost:8000
```

You also need `ffmpeg` installed for video export.

For production, use the Docker setup in the Deploy section above.

### Keys you need

| What | Where | Needed for |
|---|---|---|
| Any AI key (Anthropic, OpenAI, Gemini, OpenRouter, Groq, …), or Ollama running locally | the provider's console | Writing slideshows (required). Paste it in **Settings**. |
| Image key (optional) | OpenAI / Gemini / Together | Clean AI backgrounds. Without one, an OpenAI/Gemini writing key is reused, or Pollinations is used (free, watermarked). |
| `PEXELS_API_KEY` and/or `UNSPLASH_ACCESS_KEY` | pexels.com/api, unsplash.com/developers | Stock-photo backgrounds (optional) |
| `APIFY_TOKEN` | apify.com | Library discovery by keyword/#hashtag, and reliable profile scraping (optional) |
| `TIKTOK_CLIENT_KEY` / `TIKTOK_CLIENT_SECRET` | developers.tiktok.com | Posting and analytics |

### TikTok app setup (the part that takes the longest)

1. Create an app at developers.tiktok.com and add the **Login Kit** and **Content Posting API** products. Request the scopes `user.info.basic`, `video.upload`, `video.publish` and `video.list`.
2. Set the redirect URI to `{PUBLIC_BASE_URL}/auth/tiktok/callback`.
3. Verify your domain (or URL prefix `{PUBLIC_BASE_URL}/media/`). TikTok downloads slide images from there (`PULL_FROM_URL`), so the server has to be reachable over HTTPS on that domain.
4. Until TikTok audits your app, direct posts are forced to private (`SELF_ONLY`) and only work for test users. `MEDIA_UPLOAD` (drafts) is the usual way to start: posts land in your TikTok inbox, you add a trending sound and publish.

### How library scraping works

| Input | Method | Needs |
|---|---|---|
| Post URL | Reads the public page's embedded JSON over plain HTTP | Nothing |
| `@username` | Profile stats over HTTP; post list via headless Chromium reading TikTok's own API calls | `pip install playwright` and Chromium. TikTok often returns empty lists to datacenter IPs, so run it from a home connection. |
| `@username`, keyword, `#hashtag` | Apify's TikTok scraper | `APIFY_TOKEN` (pay-per-use) |

Tracked profiles refresh daily at 04:17. Scraping TikTok breaks its Terms of Service. The scraper only collects public data and paces its requests, but the risk of IP blocks or account action is yours.

## Tests

```bash
.venv/bin/python -m pytest -q
```

The tests stub out the AI, image and TikTok calls (40 tests). They cover:

- key → provider detection and the OpenAI-compatible JSON path with its repair retry
- Studio producing complete slideshows with AI backgrounds and saving them as an automation
- library import, search, slide reading and remix
- generate, render, edit, publish, metrics sync and the direct-post cap
- cron automations, collections and video export
- hardening: direct-post consent rules, encryption at rest with migration of plaintext rows, and the public-deploy password guard
- browser poster: queue order, one post at a time, daily cap and gap, drafts waiting for support, retries, stale claims, view counts, extension download
- country schedules: the cron round-trip, weekend rules, learning from library data, apply-timing, posting at the planned time, and DB migration

## Layout

```
app/
  llm.py         any-provider AI layer (Anthropic + OpenAI-compatible)
  imagegen.py    AI backgrounds (OpenAI, Gemini, Together, Pollinations)
  library.py     TikTok scraping, niche classification, slide reading
  store.py       dashboard-editable settings
  generator.py   slide writer + Studio format planner
  renderer.py    Pillow slide renderer
  images.py      stock search, collections, least-used picking
  pipeline.py    generate -> images -> render -> publish -> metrics
  tiktok.py      OAuth, photo posting, status, metrics
  timing.py      best posting times per country (baseline + learned)
  browser_queue.py  queue the browser extension pulls from (caps, gaps, retries)
  scheduler.py   cron jobs per automation, in its own timezone
  crypto.py      encryption at rest for keys and tokens
  legal.py       /about, /privacy, /terms pages for TikTok review
  main.py        REST API + dashboard
deploy/          install.sh, backup.sh, update.sh, Caddyfile
extension/       Chrome extension: posts the queue from your tiktok.com tab
android/         Android app: dashboard + TikTok login + background poster (shares extension/tiktok-inject.js)
static/          dashboard (vanilla JS)
fonts/           open-licence fonts (OFL)
```
