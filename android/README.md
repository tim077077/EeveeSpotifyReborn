# Slidemill for Android

All of Slidemill runs inside this one app. There is no server, no computer and no Termux: install the APK, paste an AI key, and Studio, slideshows, automations, best times, auto-posting, the library and analytics all work on the phone.

The app has three tabs:

- **Dashboard**: the same dashboard as the server version, served by the app itself on `127.0.0.1`.
- **TikTok**: tiktok.com in desktop mode. Log in here once.
- **Phone**: turn auto-posting on or off, see what it's doing, and fix battery settings.

## How it works

- **`core/`** is the Slidemill engine rewritten in Kotlin. It is a port of the Python server in `app/`, with the same prompts, providers, timing model and API. It holds:
  - the AI layer for any key, plus image generation
  - the slide layout, Studio, automations, schedules and the posting queue
  - the library and the analytics
  - the local REST API the dashboard calls, at the same `/api/v1` paths and JSON as the server

  It has no Android code, so it is tested on a normal JVM (`./gradlew :core:test`).
- **`app/`** supplies the phone parts:
  - Canvas rendering with the bundled fonts
  - MP4 export with the hardware encoder
  - API keys encrypted with the Android Keystore
  - notifications, and the share to the TikTok app
  - a foreground service that keeps the schedule running while the app is closed

  The dashboard (`static/`), the fonts (`fonts/`) and the TikTok poster (`extension/tiktok-inject.js`) are copied in at build time, so there is one source for the server, the extension and the app.

## Posting from the phone

Every automation, Studio series and Best-times schedule has two ways to post:

- **Post by itself from this phone.** At the planned time, plus 0–4 random minutes, the TikTok tab posts the slideshow through TikTok's web uploader. This needs auto-posting turned on in the Phone tab. It respects the daily cap and the minimum gap set in Settings.
- **Send to the TikTok app.** At the planned time you get a notification. Tap it and TikTok opens its own editor with the slides, and the caption is copied to your clipboard. You pick a trending sound and post. This uses no private endpoints. It is the safer option, and it's the draft flow that works today.

Slideshows are made 15 minutes before each slot.

## Install

1. Download `app-release.apk`, either from a GitHub Actions run of the "Android APK" workflow or a local build. It installs over the earlier version.
2. Open it on your phone and allow "Install unknown apps" for your browser or file manager.
3. **Dashboard → Settings**: paste any AI key (Claude, OpenAI, Gemini, OpenRouter, Groq, …), then tap **Test AI + images**.
4. **TikTok** tab: log in with email, username or phone and a password. Google and Apple login don't work inside apps.
5. **Phone** tab:
   - turn on **Post automatically** if you want hands-free posting
   - tap **Open battery settings** and set Slidemill to *Unrestricted*
   - tap **Allow exact alarms** if the button is shown

## Build

```bash
cd android
./gradlew :core:test assembleRelease   # needs JDK 17+ and the Android SDK (ANDROID_HOME)
# -> app/build/outputs/apk/release/app-release.apk
./gradlew :core:harness                # the phone engine on your computer with a fake AI, for UI work
```

Every build is signed with the same key, `slidemill-debug.keystore`, so new APKs install as updates over old ones. This is a sideloaded personal app. For a Play Store release you'd need a private key.

## Known limits (honest)

- **Not tested on a real phone or against real TikTok yet.** The engine, the dashboard and the poster's message protocol are tested here. Real-device behaviour (battery savers, the TikTok WebView, the TikTok app's share screen) is not. Make one slideshow and post it by hand before you turn on auto-posting.
- **The phone has to be on and online** at posting times. Android can still delay background work on some brands (Xiaomi, Samsung, Huawei). Use the Unrestricted battery setting and allow exact alarms.
- **Library profile scraping** reads a profile's post list through a hidden TikTok page on the phone. TikTok can change that at any time. Single post URLs and Apify discovery don't depend on it.
- **Backups** (Settings → Back up everything) hold your slideshows, automations and settings. API keys are locked to the phone's Keystore, so re-enter them after restoring on a different phone.
- **"Post by itself" breaks TikTok's terms**, because it uses TikTok's private web endpoints. Keep the daily cap low and use an account you can afford to lose. "Send to the TikTok app" doesn't have this problem.
