# Launch checklist

Work top to bottom. Each step says how long it takes and what "done" looks like. **Start step 3 first**: TikTok's review is slow, and it runs while you do everything else.

---

## 1. Server and domain (≈30 min, ≈$6/month)

1. **Buy a domain** (≈$10/year) from Cloudflare Registrar, Porkbun or Namecheap. A subdomain works too, e.g. `slides.yourname.com`.
2. **Rent a small Linux server** with Ubuntu 24.04 and at least 2 GB RAM: Hetzner CX22 (≈€4/mo), DigitalOcean ($6/mo) or Vultr. Note its IPv4 address.
3. **Point the domain at the server.** At your DNS provider, add an `A` record from `slides.yourname.com` to the server IP.
   - On Cloudflare, set the record to **DNS only** (grey cloud) so HTTPS certificates can be issued.
4. **Install Slidemill.** SSH into the server and run:
   ```bash
   git clone <your repo URL> slidemill && cd slidemill
   sudo ./deploy/install.sh
   ```
   The script asks for your domain and email. It then:
   - installs Docker
   - generates the dashboard password and the encryption key
   - checks your DNS
   - starts the app behind Caddy, which handles HTTPS automatically

   **Done when:** it prints your dashboard URL and password, and `https://<domain>/about` loads in a browser.
5. **Save the password** in a password manager. Then run `sudo ./deploy/backup.sh` once to check that backups work. **The backup's `env-*` file holds the encryption key.** Without it, stored API keys and TikTok logins can't be decrypted.

## 2. AI keys (≈10 min)

1. Open the dashboard, enter the password, and go to **Settings**.
2. Paste one AI key. **Gemini** is the cheapest option that also makes clean images with the same key. Get one at aistudio.google.com.
3. Click **Test AI + images**. **Done when:** both show ✅ and "Images" does **not** say "watermarked".
   - TikTok forbids apps from adding watermarks or logos to posted content. The free Pollinations fallback adds one, so don't post its images.
4. Optional extras:
   - Pexels key (free) for stock photos
   - Apify token for discovering library accounts by keyword or hashtag

## 3. TikTok developer app (≈1 hour of work, then days to weeks of waiting)

> TikTok's developer portal changes often. If a button has a different name, look for the closest match.

### 3a. Create the app
1. Sign in at **developers.tiktok.com** with your TikTok account and register as a developer.
2. **Manage apps → Connect an app.** Fill in:
   - **Icon:** 1024×1024 PNG
   - **Name** and **category**
   - **Description:** see the template below
   - **Platform:** Web
   - **Website URL:** `https://<domain>/about`
   - **Terms of Service URL:** `https://<domain>/terms`
   - **Privacy Policy URL:** `https://<domain>/privacy`
3. **Add products:**
   - **Login Kit.** Redirect URI: `https://<domain>/auth/tiktok/callback`
   - **Content Posting API.** Turn on **Direct Post** only if you plan to apply for it (see 3d).
   - **Display API.** This provides the `video.list` scope that analytics uses.
4. **Scopes:** `user.info.basic`, `video.upload`, `video.publish`, `video.list`.
5. **URL properties** (on the Content Posting API settings): add the **domain** `<domain>` and verify it.
   - TikTok gives you a signature string to add as a DNS record at your DNS provider.
   - TikTok downloads slide images from `https://<domain>/media/...`. They must be HTTPS with no redirects, and Slidemill already serves them that way.

### 3b. Test in sandbox before review
1. In the app, create a **Sandbox** and add your TikTok account as a target or test user.
2. Copy the sandbox **Client key** and **Client secret** into `deploy/.env` as `TIKTOK_CLIENT_KEY` / `TIKTOK_CLIENT_SECRET`. Then run `sudo docker compose --env-file deploy/.env up -d`.
3. In the dashboard, go to **TikTok accounts → Connect TikTok** and approve.
4. Make a slideshow and click **Send to drafts**. **Done when:** it appears in your TikTok app's inbox or drafts.
5. Optionally test **Post now**. Until your app passes the audit:
   - every post is forced to **Only me**
   - your TikTok account must be set to **private** while posting
   - at most **5 users** can post per 24 hours

### 3c. Record the demo video (TikTok requires one)
Record the screen on your real domain, one take, about 2–3 minutes:
1. Open `https://<domain>/about`, then sign in to the dashboard.
2. **TikTok accounts → Connect TikTok**: the TikTok login and consent screen, then return.
3. **Studio**: type a brief. The slideshows appear. Open one and edit a slide's text.
4. **Send to drafts**, then show it arriving in the TikTok app.
5. If you're applying for Direct Post, also show **Post now** and point at each item on the screen:
   - "Posting to <name>"
   - the privacy dropdown with no preset value
   - the Comments toggle, off by default
   - the commercial-content toggle with its labels
   - the Music Usage Confirmation line
   - the "may take a few minutes to appear" message
   - the post on TikTok
6. Show **Analytics** with the post's stats.

### 3d. Submit
- **App review** covers Login Kit, the scopes and the website. Explain each scope in plain words; you can copy the "Permissions used" list from `/about`.
- **Direct Post audit** is a separate step that lifts the "Only me" restriction. Apply at developers.tiktok.com/application/content-posting-api. **Read the warning below first.**

**Description template:**
> <Name> helps creators turn ideas into image slideshows and publish them to their own TikTok accounts. Users connect TikTok with Login Kit, create or edit slideshows, then send them to their TikTok drafts or post them after choosing visibility, interaction settings and commercial-content disclosure. We show statistics for posts made through <Name>.

**Common rejection reasons:**
- the website isn't reachable
- the demo video doesn't match the real app or domain
- a scope is requested but not shown being used
- a privacy option is preselected
- the posted content has a watermark

### ⚠️ The Direct Post audit is the hard part. Read this before applying.
From TikTok's Content Sharing Guidelines (checked October 2026):
- **Every upload needs the user's explicit confirmation**, after they see a preview. Unattended auto-posting at scheduled times does not fit this rule.
- **TikTok lists "a tool that posts to accounts your team manages" as unacceptable** for Direct Post. Apps must be meant for a wide audience. A private tool for your own accounts is likely to be rejected.
- Each creator account can make roughly **15 direct posts a day**, shared across all apps.

**What this means:** plan on **drafts mode** (`video.upload`) for your automations. Slidemill generates and uploads on schedule. You open TikTok, add a trending sound and tap post. That takes about 30 seconds per post, fits the rules, and usually performs better because you pick the sound. Only pursue the Direct Post audit if you turn Slidemill into a product other creators sign up for.

## 4. Turn it on (≈15 min)

1. **Library:** scrape 10–20 accounts from your niche. Run **Read slides** on the top posts and **Remix** the best format.
2. **Studio:** write your brief, make 3 slideshows, and tick "keep it going". Pick your target country and **Send to TikTok drafts**.
3. **Best times to post:** check the schedule for your country.
4. **Done when:** a draft lands in your TikTok inbox at the first scheduled time. Finish it in TikTok and post it.

## 5. First two weeks

- Post every draft yourself, 2 per day, with a trending sound.
- Don't change the automation for 7 days. Then check **Analytics**: sort by views and keep the formats that worked.
- After about 30 posts, revisit **Best times to post**. It now learns from your own results ("Confidence" goes up).

## Maintenance

| Task | Command |
|---|---|
| Update to the latest code | `sudo ./deploy/update.sh` (backs up first) |
| Back up | `sudo ./deploy/backup.sh`, then copy `backups/` off the server |
| Logs | `sudo docker compose --env-file deploy/.env logs -f --tail 100 app` |
| Restart | `sudo docker compose --env-file deploy/.env restart` |
