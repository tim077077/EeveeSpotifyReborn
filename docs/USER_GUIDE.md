# Slidemill: the full guide

Everything the app does, every tool Claude can call, and what each button on the phone does.
Written against this build (v18, branch `auto-reply-comments`).

---

## 1. What runs where

| Piece | Where it runs | Needed to use the app? |
|---|---|---|
| **The APK** | Your phone | **Yes.** It is the whole app: engine, slide renderer, fonts, the TikTok page script, the Pinterest client and the MCP server. |
| **The relay** (`relay/`) | Your free Cloudflare account | **Yes, once.** Claude can't reach `127.0.0.1` on your phone, so the app dials out to the relay and Claude talks to that URL. Deploy it from GitHub Actions, paste the address in the app once. |
| **The server** (`app/`, `static/`, `Dockerfile`, `deploy/`) | A Linux box you rent | No. That is the self-hosted dashboard version, a separate way to run Slidemill. The phone doesn't use it. |
| **The extension** (`extension/`) | Desktop Chrome | No. A second way to post from a PC. The phone has the same `tiktok-inject.js` baked into the APK. |

One phone, one account (or several), one relay. No AI key is needed on the phone: Claude is the writer and the scorer.

---

## 2. First run, in order

1. **Install the APK.** It uses the same signing key as earlier builds, so it updates over them.
2. **Battery:** tap **Allow running in the background** in section 4 and choose Allow (the line turns green: "Unrestricted ✓"). Samsung: also remove it from "sleeping apps" (**Open Slidemill's battery page**). Background passes stop if the system freezes the app.
3. **Relay:** GitHub → Actions → **Deploy Claude relay** → Run workflow. It needs two repository secrets (`CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`). The run summary prints your relay address.
4. **App → Slidemill tab → 1. Connect Claude:** paste the relay address, **Save relay address**, wait for "✓ Connected", then **Copy Claude link**.
5. **Claude:** Settings → Connectors → add a custom connector, paste the link. Claude can reach the phone whenever the app is running (the "Slidemill running" notification).
6. **App → TikTok tab:** log in with **username/email + password**. Google sign-in does not work inside apps. One account is enough to start; `+ Add account` adds more, each with its own cookies.

If Claude says the phone is offline, the app isn't running, or the relay address changed. Check the notification, then **New link** + re-add the connector if you rotated it.

---

## 3. The screens

### Slidemill tab

| Section | What it does |
|---|---|
| **1. Connect Claude** | Relay address, connection state, the connector link (the URL *is* the password), and **New link** to rotate it. |
| **2. Log in to TikTok** | Login state per account, the default account Claude posts to, and which account does the research (use a spare: research is the bot-looking part). |
| **3. Real photos (optional)** | A Pexels key gives real stock photos instead of watermarked AI images. Any AI key is reused for AI backgrounds. |
| **4. Keep it reachable** | Shows whether Android lets Slidemill run with the screen off, asks for it in one tap, and has an optional "keep the phone awake" switch for a phone on the charger. |
| **5. Scheduling** | **Let Claude schedule posts without asking me** (autopilot, on by default). On: Claude puts posts on TikTok Studio's own schedule and they go live without you. Off: Claude only proposes a time and you tap SCHEDULE. |
| **6. Answer comments for me** | The auto-replier: the on/off switch, one card per keyword (the word a comment must contain, the replies it rotates through, an optional private-account reply), the caps, **Check the comments now**, and a log of what it sent. |
| **Drafts** | Every slideshow Claude made, newest first. Tap one to preview the slides and **POST** it live, or **SCHEDULE** it for the time Claude proposed. |

### Analytics tab

Followers and their weekly change, totals, last 7 days vs the 7 before, views per week, slideshows vs videos, top posts, every post with views/likes/comments/shares/saves and views per day, plus what Claude's virality score is actually worth on your account.

### TikTok tab

tiktok.com in desktop mode, one profile per account. This is where the session lives that posts drafts, reads comments and answers them. Nothing here is a scrape of a public page: it is your own logged-in tab doing what it would do if you clicked.

---

## 4. Every tool Claude can call (23)

Claude calls these itself when you ask for something. You never type a tool name. The point of the table is knowing what is possible.

| Tool | What it does | You say something like |
|---|---|---|
| `writing_guide` | The house rules for hooks and slide structure, read once per conversation | (automatic) |
| `research_library` | Your scraped library, best first by "lift" (views vs that account's usual) | "what works in my niche?" |
| `add_to_library` | Import TikTok accounts (usernames/profile URLs) or single post URLs | "add these 3 accounts to my library" |
| `discover_slideshows` | Search TikTok's photo results, list the top carousels, import their accounts | "find the biggest carousels about cheap travel" |
| `carousel_formats` | Which formats (slide count, words per slide, hook style, ending, sound) beat their account's usual, with the best examples' slides as images | "what format should I copy?" |
| `read_library_slides` | Library posts whose slide text hasn't been read, tiled one image per post | (automatic while researching) |
| `save_slide_texts` | Store the slide text Claude read off those images | (automatic) |
| `create_slideshow` | Render a slideshow Claude wrote: backgrounds from each slide's `image_query`/`image_prompt`, text drawn on top, slides returned as one tiled image | "make me 3 slideshows about X" |
| `import_slides` | Use finished slide images Claude made itself (https links, or small `data:` URIs) | "I made the slides, here they are" |
| `save_draft` | Save a slideshow to TikTok **Studio web** drafts now | "put it in my web drafts" |
| `schedule_slideshow` | Put a slideshow on TikTok Studio's own schedule (15 min to 30 days ahead) | "schedule the best one for tonight" |
| `view_post` | One of your posted TikToks: numbers, and for a carousel its real slides as posted | "how did my best post look?" |
| `edit_slideshow` | Fixes a rendered slideshow: slide text, a new background, delete or add slides, caption | "slide 3 has a typo", "swap the background on slide 2" |
| `review_slideshow` | Claude records its check of the current slides and claims; required before scheduling | (automatic after every render) |
| `view_slideshow` | One slideshow's details, draft status and rendered slides | "show me that draft again" |
| `list_slideshows` | Recent slideshows with status, post/schedule and score | "what have you made?" |
| `delete_slideshow` | Delete a slideshow from the phone (never from TikTok) | "delete the weak one" |
| `predict_virality` | Two-step score: Claude reads the slides blind, then Slidemill turns that read into a score, attention curve and rank | "score these before I post" |
| `analytics` | The account's real numbers, what beats its median, and whether the score tracks views | "how am I doing this week?" |
| `best_times` | Posting times for an audience's country | "when should I post for the UK?" |
| `auto_reply` | The comment auto-replier: `status`, `set` (keyword + replies + optional private reply), `remove`, `on`, `off` | "answer everyone who comments PLAN" |
| `pinterest` | Explore Pinterest: a contact sheet of real portrait pins plus their shapes and source domains | "find me beige bedroom backgrounds" |
| `phone_status` | Which accounts are logged in, where backgrounds come from, library size, phone time, autopilot | "is everything connected?" |

### Parameters that matter

- **`create_slideshow`**: `plan` (required: title, caption, description, `text_position`, and `slides[]` with `role`, `text_items[]`, `image_query`, `image_prompt`), `image_source` = `auto` | `stock` | `ai` | `pinterest`, `aspect_ratio` = `4:5` | `9:16` | `1:1`, `save_draft`, `account`.
- **`import_slides`**: `slides` (2 to 35 https links or small data URIs, whole call under ~900 KB), `caption` (max 89 chars), `description` (hashtags), `title`, `save_draft`, `account`.
- **`schedule_slideshow`**: `slideshow_id`, `at` (ISO 8601, e.g. `2026-10-30T18:30:00+02:00`), `sound` (default true), `account` (required when several accounts are logged in).
- **`pinterest`**: `query` (required), `count` (4-12, default 8), `shape` = `portrait` | `tall` | `square` | `any`.
- **`auto_reply`**: `action` = `status` | `set` | `remove` | `on` | `off`, `keyword`, `replies` (one per line), `private_reply`.
- **`analytics`**: `refresh` (default true, skipped within 10 minutes), `weeks`, `posts`, `account`.
- **`best_times`**: `country` (two-letter), `posts_per_day`.

### Quirks worth knowing

- Slow work answers with the slideshow's id after ~45 seconds; the phone keeps going and `view_slideshow` shows the outcome. Claude's apps give up on a tool call after about a minute, so this is by design.
- Slide images come back **tiled on one image**: 7 separate slides would cost about three times the tokens. `view_slideshow` `slide=N` shows one full size.
- `predict_virality` with `seen_slides: true` doesn't resend slides Claude already looked at.
- Every answer is trimmed (empty fields dropped, similar rows made into one table, TikTok links as post ids, times in your phone's zone).
- The tool list itself has a token budget, and a test fails if it grows: that's why the descriptions are terse.

---

## 5. Workflows

### Make a carousel
1. "Look at my best posts and tell me what's working." (`analytics`)
2. "Write 3 slideshows about X in that style." (`writing_guide`, `create_slideshow`)
3. After every render Claude checks each slide (text cut off, typos, readability, faces covered), every factual claim, and every background, fixes problems itself with `edit_slideshow`, and records the check with `review_slideshow`. The app's list shows "reviewed ✓", "review failed" or "not reviewed".
4. "Score them and schedule the best for tonight." (`predict_virality`, `best_times`, `schedule_slideshow`)

### Backgrounds from Pinterest
1. "Find me portrait Pinterest photos of a dark green kitchen." (`pinterest` returns a contact sheet)
2. Claude writes each slide's `image_query` from what it saw.
3. "Render it with Pinterest backgrounds." (`create_slideshow` with `image_source=pinterest`)
4. The pick is downloaded once into the image cache and reused by URL. If Pinterest has nothing, that slide falls back to AI.

### Auto-reply to a keyword
1. App → Slidemill → **6. Answer comments for me** → tick the box.
2. Keyword `PLAN`, replies one per line (they rotate), optional private-account reply like `ai cont privat`.
3. **Check the comments now** to see it work once, then leave it alone.
4. Or ask Claude: "answer every comment that says PLAN with these two lines" (`auto_reply` `set`).

How matching works: case-, accent- and punctuation-insensitive, whole word, **1 typo** allowed. `plan`, `PLAN`, `Plan!`, `pln` all match. `planuri` (a different word) and `planning` don't. Set the keyword to `planuri` too if you want the plural.

### Research competitors
1. "Add these 5 accounts to my library." (`add_to_library`)
2. "What format do the biggest carousels use?" (`discover_slideshows`, `carousel_formats`)
3. Remix the format, never the words: the library text is other people's posts.

---

## 6. Guards and limits (all in the app)

| Guard | Default | Where to change it |
|---|---|---|
| Review before posting | Claude can't schedule a slideshow until its current render passed `review_slideshow` (any edit means a new review; 3 failed rounds → Claude asks you) | built in; your own POST button in the app isn't gated |
| Posts closer together | 2 hours apart, max 4 in 24 h | built in, not configurable |
| Scheduling window | 15 min to 30 days ahead | TikTok's own limit |
| Auto-reply rate | 8 an hour, 25 a day | Auto-reply card |
| Auto-reply gap | 90-420 s between replies, random | Auto-reply card |
| Quiet hours | nothing between 01:00 and 07:00 | Auto-reply card |
| Same person again | 6 hours | Auto-reply card |
| Deliberate misses | 8% of matches let by | Auto-reply card |
| Comment window | your posts from the last 7 days | Auto-reply card |
| Post list re-read | at most every 15 minutes | Auto-reply card |
| Comment reads | only for a post whose comment count moved | automatic |
| A comment answered | once, ever | automatic |
| Caption length | 89 characters | TikTok |
| Slides per carousel | 35 | TikTok |

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Claude says the phone is offline | App not running, or the relay address changed | Open the app, check the notification; re-save the relay address |
| A tool is missing in Claude | Connector session cached the old tool list | Reconnect the connector or start a new chat |
| "tiktok.com isn't answering in the app" | The TikTok tab never loaded or you're logged out | App → TikTok tab, check the page, log in again |
| "Not logged in to TikTok in the app" | Session expired | Log in again in the TikTok tab (username/password) |
| "TikTok refused the reply" / rate limit | Too fast, or a temporary block | Leave it alone for an hour; lower the caps |
| "0 comments read" on a post that has comments | Comment list refused, or the count didn't move | Tap **Check the comments now**; if it persists, the endpoint changed |
| Pinterest "403 ... wants a browser-shaped request" | Missing the XHR headers | Shouldn't happen in the app; report it |
| Backgrounds missing on some slides | No pin/photo for that query | Reword `image_query`; AI is the fallback |
| A scheduled post didn't appear | TikTok Studio review delay | Check TikTok Studio → Content → Scheduled |
| Auto-reply won't answer anything | Off, outside hours, cap reached, or same person inside 6 h | The card shows the reason in **Last check** |

---

## 8. Risk, plainly

- **Posting and replying from your logged-in web tab uses TikTok's private endpoints.** That is against their terms. Keep the caps where they are, don't run it on an account you can't lose, and never post the same slideshow to two accounts.
- **Auto-replying to comments is the most machine-looking thing here.** That's why the defaults are slow, jittered, quiet at night, capped, and why it misses some on purpose.
- **Pinterest photos belong to their owners.** Reading public search is one thing; republishing someone's photo on TikTok is a copyright decision per image. The pin's source domain is kept so you can credit it, and Pexels or AI are there when you want something you can reuse freely.
- **Research** (search, other accounts' profiles) is the bot-looking part of the product. Give it a spare account.
- One phone, one IP. TikTok can link accounts that share it.

---

## 9. Your data

The phone keeps everything under its app storage (`files/slidemill/`): `db/` is one JSON file per table (slideshows, images, library, auto-reply log), `media/` holds rendered slides and downloaded backgrounds. Deleting the app deletes all of it. Nothing is sent anywhere except the AI/stock/Pinterest requests needed for a job and your own TikTok session.

---

## 10. For developers

```
android/            the phone app (Kotlin)
  core/             engine with no Android code, tested on a normal JVM: ./gradlew :core:test
  app/              Activity, WebView (TikTokWeb), foreground service, Android platform
extension/          tiktok-inject.js: the in-page poster, shared by the phone and the extension
relay/              Cloudflare Worker: carries Claude's calls to the phone
app/ static/        the optional Python server + dashboard
docs/               this guide, CLAUDE_CONNECTOR.md, VIRALITY.md, LAUNCH_CHECKLIST.md
```

Build: `cd android && ./gradlew assembleRelease` (JDK 17). The build copies `extension/tiktok-inject.js` and `fonts/` into the APK's assets, so there is one source of truth for both. `./gradlew :core:test` runs 65 tests, including the TikTok and Pinterest request shapes against fakes.
