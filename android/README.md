# Slidemill for Android

The phone app is built for one way of working: **you talk to Claude, and Claude does the job**. Claude researches your niche, writes the slideshows, looks at the rendered slides and scores them. The phone renders the slides and keeps them as drafts in the app; you preview one and tap **POST** to publish it live. There is no AI key, no schedule, no queue and no notification to tap.

The app has three tabs:

- **Slidemill**: connect Claude (relay address, connector link), check you're logged in to TikTok, add an optional Pexels key for real photos, and see Claude's slideshows as **Drafts**: tap one to preview it and post it.
- **Analytics**: your account's numbers. It shows:
  - tiles: views in the last 7 days against the week before, followers and their weekly change, total and average views, engagement, likes, comments, shares and saves;
  - a views-per-week chart;
  - slideshows against videos;
  - **What works for you**: which slide counts, posting times, days, formats and hashtags beat your median views (groups of 3+ posts that are 2+ days old, once you have 6 such posts);
  - every post with its views, likes, comments, shares, saves, engagement, views per day and views in the last 24 hours (tap one to open it).
  
  The phone snapshots your posts every 3 hours (and when you tap Refresh). It reads them from TikTok Studio's content list, the same list as tiktok.com/tiktokstudio/content, so every published post is counted (up to 100); scheduled posts are left out until they go live. That list has no sound info, so `what_works` can't group by sound. TikTok only shows current totals, so growth numbers (views gained per week or in 24 hours, followers gained) appear once there are snapshots to compare. Claude gets the same report from the `analytics` tool.
- **TikTok**: tiktok.com in desktop mode. Log in here once. **+ Add account** logs in another TikTok account in its own profile (separate cookies); the Slidemill tab picks which one Claude posts to by default and which one does the research.

## How you post one

Tap a draft on the Slidemill tab: you see the slides (swipe), the carousel title and the caption. Tap **POST** and the phone's logged-in TikTok page uploads the slides and publishes the photo post; the draft then shows "✓ Live on TikTok" with a link to the post. A second tap can't post it twice.

- **Add a sound** (on by default) attaches TikTok's top suggested sound for those slides from its web library. That library is smaller than the app's, and you don't choose the sound. Untick it to post silent.
- **Schedule:** if Claude proposed a time (`schedule_slideshow`), the big button reads **SCHEDULE for Tue 27 Oct, 18:30**; one tap puts the post on TikTok Studio's own schedule. **Schedule for another time…** lets you pick a date and time yourself. TikTok then publishes it at that time from its servers, phone on or off, and it shows in TikTok Studio → Content → Scheduled. TikTok accepts 15 minutes to 30 days ahead (some accounts only 10 days).
- **Autopilot** ("Let Claude schedule posts without asking me", Slidemill tab, **on by default**): Claude's `schedule_slideshow` schedules on TikTok directly, and the post goes public at that time without you seeing it. A guard in the app keeps Slidemill posts at least 2 hours apart and at most 4 in 24 hours. Untick it and Claude only proposes times for your one-tap SCHEDULE.
- **Answer comments for me** (Slidemill tab, **off by default**): set a keyword a comment must contain (`PLAN`) and what to answer. The phone reads the comments on your own posts from the last 7 days every minute and answers the matching ones by itself, with nobody tapping anything. Matching ignores case, accents and one typo, so `plan`, `PLAN`, `Plan!` and `pln` all match. Each keyword has a list of replies that rotate, and an optional reply sent instead when that commenter's account is private (for example "ai cont privat"). Every comment is answered at most once ever, and the guards are yours to set: 20 an hour, 50 a day, 1-3 minutes apart, both by default. The card shows what went out, when, and to which comment. Claude can set the same thing with the `auto_reply` tool.
- **Edit in the TikTok app instead** opens the TikTok app's photo editor with the slides and the caption copied, if you want to pick a trending sound yourself.

## Web drafts (optional)

Only if you want a copy in TikTok Studio's web drafts. Those drafts live on tiktok.com/tiktokstudio, **not** in the TikTok app's Drafts (the app's Drafts only holds drafts made on that phone). The **Web drafts** button on the Slidemill tab opens them in the TikTok tab.

1. Claude calls `create_slideshow` (Claude writes, the phone draws the text over backgrounds) or `import_slides` (Claude made the finished images itself and passes them as links), with `save_draft: true`; or `save_draft` later.
2. The phone renders or downloads the slides, then hands them to its logged-in tiktok.com page (`extension/tiktok-inject.js`). The page uploads them and makes TikTok Studio's own "Save draft" request.
3. Claude's tool call answers with **TikTok's draft id**, or with the exact reason it failed: not logged in, TikTok refused, tiktok.com didn't load. The slideshow remembers the result, and the Slidemill tab shows it ("✓ in TikTok drafts" or "✗ not saved: …").

If the work takes longer than Claude waits (about 45 seconds), Claude gets the slideshow's id; the phone finishes, records the result, and `view_slideshow` shows it. Nothing waits in a queue for a timer.

Web drafts are never public. Open them with **Web drafts** in the app or on tiktok.com/tiktokstudio → Drafts.

## Install

1. Download `app-release.apk`, either from a GitHub Actions run of the "Android APK" workflow or a local build. It installs over the earlier version: don't uninstall, or you lose the TikTok login and the relay link.
2. **Slidemill** tab: paste your relay address, tap **Save relay address**, then **Copy Claude link** and add it in Claude (Settings → Connectors → Add custom connector). See [docs/CLAUDE_CONNECTOR.md](../docs/CLAUDE_CONNECTOR.md) for deploying the relay.
3. **TikTok** tab: log in with email or username and a password. Google and Apple login don't work inside apps.
4. Optional: paste a free Pexels key for real stock photos. Without one, backgrounds are free AI images, which are slower and watermarked.
5. **Open battery settings** and set Slidemill to *Unrestricted*. On Samsung, also take it out of "sleeping apps".

## How it's built

- **`core/`** is the engine, in Kotlin with no Android code, so it is tested on a normal JVM (`./gradlew :core:test`):
  - `Mcp.kt`: the connector tools
  - `Studio.kt`: rendering, and the draft save with its recorded result
  - `Library.kt`: the research library
  - `Virality.kt`: scoring, with Claude as the reader
  - `Timing.kt`: best times
  - `Relay.kt`: the connection to the relay
- **`app/`** supplies the phone parts:
  - Canvas rendering with the bundled fonts
  - the tiktok.com WebView and `AndroidTikTok`, which asks it to save a draft and waits for TikTok's answer
  - keys encrypted with the Android Keystore
  - a foreground service that keeps the relay connection and the TikTok page alive while the app is closed

The fonts (`fonts/`) and the TikTok page script (`extension/tiktok-inject.js`) are copied in at build time, so the extension and the app share one source.

## Build

```bash
cd android
./gradlew :core:test assembleRelease   # needs JDK 17+ and the Android SDK (ANDROID_HOME)
# -> app/build/outputs/apk/release/app-release.apk
```

Every build is signed with the same key, `slidemill-debug.keystore`, so new APKs install as updates over old ones. This is a sideloaded personal app. For a Play Store release you'd need a private key.

## Known limits (honest)

- **The phone has to be on, online and running Slidemill** when Claude calls. Android can still freeze background apps on some brands (Samsung, Xiaomi, Huawei); use the Unrestricted battery setting.
- **Saving drafts uses TikTok's private web endpoints**, which is against TikTok's terms. Use an account you can afford to lose. If TikTok changes its web uploader, saves fail with TikTok's error, and that error reaches Claude.
- **Library profile scraping** reads a profile's post list through a hidden TikTok page on the phone. TikTok can change that at any time. Single post URLs don't depend on it.
