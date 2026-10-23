# Slidemill for Android

The browser extension, as a phone app. It has three tabs:

- **Dashboard**: your Slidemill dashboard (Studio, automations, library, analytics).
- **TikTok**: tiktok.com in desktop mode. Log in here once.
- **Poster**: turns auto-posting on or off, shows status, and holds the server address and password.

A background service does the extension's job. About once a minute it asks Slidemill for the next due post, downloads the slides, and posts them through the TikTok tab's logged-in session. It runs the same `tiktok-inject.js` as the extension, copied in at build time, so there is one source for both. Draft jobs stay queued until drafts are wired up.

## What still runs on a computer

The Slidemill **server** (AI writing, image generation, rendering, scheduling) still runs on your PC or a server. The app connects to it.

- **Same Wi-Fi:** start the server with `uvicorn app.main:app --host 0.0.0.0 --port 8000`, and set `SLIDEMILL_API_KEY`, because anyone on the network can reach it. In the app, enter `http://<your PC's IP>:8000`.
- **Anywhere:** deploy with `deploy/install.sh` and enter `https://your-domain`.

## Install

1. Download `app-release.apk`, either from a GitHub Actions run of the "Android APK" workflow or a local build.
2. Open it on your phone and allow "Install unknown apps" for your browser or file manager.
3. **Poster** tab: enter the Slidemill address and password, then tap **Save & test connection**.
4. **TikTok** tab: log in with email, username or phone and a password. Google and Apple login don't work inside apps.
5. **Poster** tab: turn on **Post automatically**, tap **Open battery settings**, and set Slidemill to *Unrestricted*.

## Build

```bash
cd android
./gradlew assembleRelease   # needs JDK 17+ and the Android SDK (ANDROID_HOME)
# -> app/build/outputs/apk/release/app-release.apk
```

Every build is signed with the same key, `slidemill-debug.keystore`, so new APKs install as updates over old ones. This is a sideloaded personal app. For a Play Store release you'd need a private key.

## Known limits (honest)

- **Untested against real TikTok in an app WebView.** TikTok may treat the in-app browser differently from Chrome on a desktop. Run one manual post with auto-posting off before you rely on it.
- **Android can pause background apps** even with a foreground service, especially Xiaomi, Samsung and Huawei phones with aggressive battery savers. The "Unrestricted" battery setting is required.
- **Same rules as the extension:** this uses TikTok's private web endpoints, which is against TikTok's terms. Keep Slidemill's daily cap low and use a spare account first.
