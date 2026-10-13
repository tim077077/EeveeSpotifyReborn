# Slidemill

A self-hosted TikTok slideshow factory. Give it a topic and it writes a listicle slideshow with Claude, finds background images, and renders the slides. It can post them to TikTok on a schedule, as drafts or as public posts, and it tracks how each post does.

It was built by tearing down how reel.farm works; see [docs/REELFARM_TEARDOWN.md](docs/REELFARM_TEARDOWN.md).

## Features

- **AI slideshow generator.** You give it a prompt; Claude returns hook, body and CTA slides with styling, a caption and a description. Recent titles go back into the prompt so new slideshows don't repeat old ones.
- **Slide renderer.** TikTok-style outline text and boxed text, 7 fonts, 4 aspect ratios, grid layouts, darkening overlays and Korean/Japanese/Chinese text. Every slide can be edited in the dashboard and re-rendered.
- **Automations.** Each automation has a list of hooks that it rotates through, a style prompt, a language, an optional product for a soft CTA, image settings and posting times (cron). Automations can be paused, resumed or run now.
- **Images.** Upload your own image collections or import them from Pexels/Unsplash. You can also let it search stock photos for each slide automatically. Collections are cycled least-used first so images don't repeat.
- **TikTok posting.** Connect any number of accounts with OAuth. Posts go out as photo carousels, either straight to your TikTok drafts (`MEDIA_UPLOAD`) or published directly (`DIRECT_POST`). The direct-post daily limit is enforced, with automatic fallback to drafts.
- **Analytics.** It polls each post's publish status and syncs views, likes, comments and shares every 10 minutes.
- **MP4 export** for Reels and Shorts, plus a **REST API** (`/api/v1`, docs at `/docs`).

## Run it

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env        # fill in keys
.venv/bin/uvicorn app.main:app --port 8000
# open http://localhost:8000
```

You also need `ffmpeg` installed for video export.

You can run it with Docker instead: `docker build -t slidemill . && docker run -p 8000:8000 --env-file .env -v slidemill:/data slidemill`

### Keys you need

| What | Where | Needed for |
|---|---|---|
| `ANTHROPIC_API_KEY` | console.anthropic.com | Writing slideshows (required) |
| `PEXELS_API_KEY` and/or `UNSPLASH_ACCESS_KEY` | pexels.com/api, unsplash.com/developers | Automatic backgrounds (optional if you upload collections) |
| `TIKTOK_CLIENT_KEY` / `TIKTOK_CLIENT_SECRET` | developers.tiktok.com | Posting and analytics |

### TikTok app setup (the part that takes the longest)

1. Create an app at developers.tiktok.com and add the **Login Kit** and **Content Posting API** products. Request the scopes `user.info.basic`, `video.upload`, `video.publish` and `video.list`.
2. Set the redirect URI to `{PUBLIC_BASE_URL}/auth/tiktok/callback`.
3. Verify your domain (or URL prefix `{PUBLIC_BASE_URL}/media/`). TikTok downloads slide images from there (`PULL_FROM_URL`), so the server has to be reachable over HTTPS on that domain.
4. Until TikTok audits your app, direct posts are forced to private (`SELF_ONLY`) and only work for test users. `MEDIA_UPLOAD` (drafts) is the usual way to start: posts land in your TikTok inbox, you add a trending sound and publish.

## Tests

```bash
.venv/bin/python -m pytest -q
```

The tests stub out Claude and TikTok, then cover the whole flow: generate, render, edit and re-render, publish, the status and metrics sync, the direct-post cap falling back to drafts, automations with cron, hook rotation and pausing, collections, and video export.

## Layout

```
app/
  generator.py   Claude prompt + structured SlideshowPlan
  renderer.py    Pillow slide renderer
  images.py      stock search, collections, least-used picking
  pipeline.py    generate -> images -> render -> publish -> metrics
  tiktok.py      OAuth, photo posting, status, metrics
  scheduler.py   cron jobs per automation
  main.py        REST API + dashboard
static/          dashboard (vanilla JS)
fonts/           open-licence fonts (OFL)
```
