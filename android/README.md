# Slidemill for Android

The phone app is built for one way of working: **you talk to Claude, and Claude does the job**. Claude researches your niche, writes the slideshows, looks at the rendered slides, scores them, and saves the ones you want straight to your TikTok Studio drafts. The phone renders the slides and saves the drafts. There is no AI key, no schedule, no queue and no notification to tap.

The app has two tabs:

- **Slidemill**: connect Claude (relay address, connector link), check you're logged in to TikTok, add an optional Pexels key for real photos, and see each slideshow Claude made and whether it reached your TikTok drafts.
- **TikTok**: tiktok.com in desktop mode. Log in here once.

## How a draft gets saved

1. Claude calls `create_slideshow` (with `save_draft: true`) or `save_draft`.
2. The phone renders the slides, then hands them to its logged-in tiktok.com page (`extension/tiktok-inject.js`). The page uploads them and makes TikTok Studio's own "Save draft" request.
3. Claude's tool call answers with **TikTok's draft id**, or with the exact reason it failed: not logged in, TikTok refused, tiktok.com didn't load. The slideshow remembers the result, and the Slidemill tab shows it ("✓ in TikTok drafts" or "✗ not saved: …").

If the work takes longer than Claude waits (about 45 seconds), Claude gets the slideshow's id; the phone finishes, records the result, and `view_slideshow` shows it. Nothing waits in a queue for a timer.

Drafts are never public. Open TikTok → Profile → Drafts (or TikTok Studio on the web), add a sound and post.

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
