/*
 * Slidemill TikTok Poster — tiktok-inject.js (based on the original VintedBot poster)
 * In-page TikTok photo-carousel poster. Reverse-engineered from TikTok Studio's
 * own web upload flow (no official API, no developer app). RUN IT INSIDE A
 * LOGGED-IN tiktok.com TAB — paste into the DevTools console, or load it as a
 * userscript / content script. It reuses YOUR session cookies and TikTok's own
 * request-signing (webmssdk), so there is no crypto to maintain.
 *
 * ⚠️  Automating TikTok is against their Terms of Service — accounts can be
 *     limited or banned. Use sparingly, at your own risk. A sound from TikTok's
 *     web library CAN be attached (pass sound:true) — it's smaller than the
 *     phone app's full viral catalog, but real. Default is silent.
 *
 * Flow (all same-origin, chained from each response — see README):
 *   0. GET  /api/v1/video/upload/auth/            -> STS security token
 *   1. GET  /top/v1?Action=ApplyImageUpload       -> StoreUri, UploadHost, SessionKey (per image)
 *   2. POST https://<UploadHost>/upload/v1/<Uri>  -> upload the bytes (Content-CRC32)
 *   3. POST /top/v1?Action=CommitImageUpload      -> final image Uri + dimensions
 *   4. POST /tiktok/web/project/post/v1/          -> create the post (auto-signed in-page)
 *      or POST /api/v1/draft/create_update/      -> save it to TikTok Studio's drafts instead
 *
 * Usage in the console (after pasting this file):
 *   await VBTikTok.postFromPicker({
 *     title: "3 reasons you're not making money on Vinted",   // shows on the carousel
 *     caption: "👀 Save this…\n\n#vinted #reselling #depop",   // description + hashtags
 *   });
 *   // a file dialog opens — select your slide-1.png … slide-N.png (in order).
 *
 * Or if you already have image Blobs/Files/data-URLs/URLs in JS:
 *   await VBTikTok.post({ title, caption, images: [file1, file2, …] });
 */
(function () {
  "use strict";

  const ORIGIN = "https://www.tiktok.com";
  const SERVICE_ID = "photomode";
  const log = (...a) => console.log("%c[Slidemill]", "color:#fe2c55;font-weight:bold", ...a);
  const warn = (...a) => console.warn("[Slidemill]", ...a);

  // ---- small helpers --------------------------------------------------------

  const rand = (n) => {
    const c = "abcdefghijklmnopqrstuvwxyz0123456789";
    let s = "";
    for (let i = 0; i < n; i++) s += c[(Math.random() * c.length) | 0];
    return s;
  };
  // nanoid-ish 21-char id for creation_id (matches TikTok's shape).
  const creationId = () => {
    const c = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let s = "";
    for (let i = 0; i < 21; i++) s += c[(Math.random() * c.length) | 0];
    return s;
  };
  const amzDate = () => new Date().toISOString().replace(/[-:]/g, "").replace(/\.\d+/, "");

  // CRC32 (matches the Content-CRC32 header TikTok's uploader sends).
  const CRC_TABLE = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c >>> 0;
    }
    return t;
  })();
  const crc32Hex = (bytes) => {
    let c = 0xffffffff;
    for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
    c = (c ^ 0xffffffff) >>> 0;
    return c.toString(16).padStart(8, "0");
  };
  const sha256Hex = async (buf) => {
    const h = await crypto.subtle.digest("SHA-256", buf);
    return [...new Uint8Array(h)].map((b) => b.toString(16).padStart(2, "0")).join("");
  };

  // Decode the USER_ID out of the per-file Auth JWT (used for the X-Storage-U
  // header). Falls back to scanning page state if the token shape changes.
  const b64urlJson = (s) => {
    try { return JSON.parse(decodeURIComponent(escape(atob(s.replace(/-/g, "+").replace(/_/g, "/"))))); }
    catch (_) { try { return JSON.parse(atob(s.replace(/-/g, "+").replace(/_/g, "/"))); } catch (_) { return null; } }
  };
  const b64urlRaw = (s) => {
    try {
      s = String(s || "").replace(/-/g, "+").replace(/_/g, "/");
      while (s.length % 4) s += "=";
      return decodeURIComponent(escape(atob(s)));
    } catch (_) { try { return atob(s); } catch (_) { return ""; } }
  };
  const storageUserIdFromAuth = (authStr) => {
    // Auth = "SpaceKey/photomode/1/2:version:v2:<jwt>"; the JWT payload carries
    // signatureInfo.extra.tos_meta -> {"USER_ID":"…"}. Decode the payload and
    // pull the first long digit run after a USER_ID label (robust to escaping).
    const jwt = String(authStr || "").split(":").pop();
    const parts = jwt.split(".");
    if (parts.length < 2) return null;
    const raw = b64urlRaw(parts[1]);
    const m = raw.match(/USER_ID[^0-9]{0,10}(\d{6,})/);
    if (m) return m[1];
    // last resort: any TikTok page global that exposes the numeric uid
    try {
      const u = window.__UNIVERSAL_DATA_FOR_REHYDRATION__;
      const s = JSON.stringify(u || {});
      const g = s.match(/"(?:uid|userId|user_id)"\s*:\s*"?(\d{6,})/);
      if (g) return g[1];
    } catch (_) {}
    return null;
  };

  // Normalise any image input to a Uint8Array + a Blob.
  async function toBytes(img) {
    if (img instanceof Blob) return new Uint8Array(await img.arrayBuffer());
    if (img instanceof ArrayBuffer) return new Uint8Array(img);
    if (img instanceof Uint8Array) return img;
    if (typeof img === "string") {
      if (img.startsWith("data:")) {
        const b = atob(img.split(",")[1]);
        const u = new Uint8Array(b.length);
        for (let i = 0; i < b.length; i++) u[i] = b.charCodeAt(i);
        return u;
      }
      // treat as a URL
      const r = await fetch(img);
      return new Uint8Array(await r.arrayBuffer());
    }
    throw new Error("unsupported image input");
  }

  // Read the image's pixel dimensions.
  const dimsOf = (bytes) =>
    new Promise((resolve) => {
      const url = URL.createObjectURL(new Blob([bytes]));
      const im = new Image();
      im.onload = () => { resolve({ w: im.naturalWidth, h: im.naturalHeight }); URL.revokeObjectURL(url); };
      im.onerror = () => { resolve({ w: 1080, h: 1920 }); URL.revokeObjectURL(url); };
      im.src = url;
    });

  // ---- AWS SigV4 (imagex ApplyImageUpload / CommitImageUpload) --------------
  // The imagex gateway on /top/v1 authenticates with a real SigV4 Authorization
  // header signed by the STS creds from /api/v1/video/upload/auth/. (Chrome
  // strips Authorization from exported HARs, which is why it looked absent.)
  const REGION = "gcp";      // from ApplyImageUpload ResponseMetadata.Region
  const SERVICE = "imagex";  // from ResponseMetadata.Service
  const EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  const toHex = (u) => [...u].map((b) => b.toString(16).padStart(2, "0")).join("");
  async function hmac(keyBytes, msg) {
    const k = await crypto.subtle.importKey("raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    return new Uint8Array(await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(msg)));
  }
  const rfc3986 = (s) => encodeURIComponent(s).replace(/[!'()*]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase());

  // Returns { qs, headers } — sign, then send with that exact query string.
  async function sigv4(method, path, query, payloadSha, creds) {
    const amz = amzDate();
    const date = amz.slice(0, 8);
    const host = "www.tiktok.com";
    const qs = Object.keys(query).sort().map((k) => rfc3986(k) + "=" + rfc3986(query[k])).join("&");
    const signedHeaders = "host;x-amz-content-sha256;x-amz-date;x-amz-security-token";
    const canonicalHeaders =
      "host:" + host + "\n" +
      "x-amz-content-sha256:" + payloadSha + "\n" +
      "x-amz-date:" + amz + "\n" +
      "x-amz-security-token:" + creds.token + "\n";
    const canonicalRequest = [method, path, qs, canonicalHeaders, signedHeaders, payloadSha].join("\n");
    const crHash = await sha256Hex(new TextEncoder().encode(canonicalRequest));
    const scope = date + "/" + REGION + "/" + SERVICE + "/aws4_request";
    const stringToSign = ["AWS4-HMAC-SHA256", amz, scope, crHash].join("\n");
    let key = new TextEncoder().encode("AWS4" + creds.sk);
    key = await hmac(key, date);
    key = await hmac(key, REGION);
    key = await hmac(key, SERVICE);
    key = await hmac(key, "aws4_request");
    const sig = toHex(await hmac(key, stringToSign));
    const headers = {
      "Authorization": "AWS4-HMAC-SHA256 Credential=" + creds.ak + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + sig,
      "X-Amz-Date": amz,
      "X-Amz-Security-Token": creds.token,
      "X-Amz-Content-Sha256": payloadSha,
    };
    return { qs, headers };
  }

  // ---- pipeline steps -------------------------------------------------------

  async function getCreds() {
    const r = await fetch(ORIGIN + "/api/v1/video/upload/auth/", { credentials: "include" });
    const j = await r.json();
    const b = j.video_token_v5 || j.vframe_token_v5 || j.audio_token_v5;
    if (!b || !b.access_key_id) throw new Error("could not read upload creds from /api/v1/video/upload/auth/ — shape changed; log the response");
    return { ak: b.access_key_id, sk: b.secret_acess_key || b.secret_access_key, token: b.session_token };
  }

  async function applyImageUpload(creds, fileSize) {
    const query = {
      Action: "ApplyImageUpload",
      Version: "2018-08-01",
      ServiceId: SERVICE_ID,
      FileSize: String(fileSize),
      s: rand(11),
      device_platform: "web",
    };
    const { qs, headers } = await sigv4("GET", "/top/v1", query, EMPTY_SHA, creds);
    const r = await fetch(ORIGIN + "/top/v1?" + qs, { credentials: "include", headers });
    const j = await r.json();
    const addr = j.Result && j.Result.UploadAddress;
    if (!addr || !addr.StoreInfos || !addr.StoreInfos[0]) throw new Error("ApplyImageUpload failed: " + JSON.stringify(j).slice(0, 300));
    return {
      storeUri: addr.StoreInfos[0].StoreUri,
      auth: addr.StoreInfos[0].Auth,
      uploadHost: addr.UploadHosts[0],
      sessionKey: addr.SessionKey,
    };
  }

  const bytesToB64 = (bytes) => {
    let bin = "";
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    return btoa(bin);
  };

  // Ask the extension background worker to POST the bytes (it is NOT bound by
  // TikTok's page CSP, which blocks a direct cross-origin fetch to the CDN).
  // Resolves { handled, json }; rejects (→ direct-fetch fallback) if no bridge.
  function bridgeUpload(url, crc, storageU, bytes) {
    return new Promise((resolve, reject) => {
      const id = "vb" + Math.random().toString(36).slice(2);
      let done = false;
      const onMsg = (ev) => {
        if (ev.source !== window) return;
        const d = ev.data;
        if (d && d.__vbtt === "uploadResult" && d.id === id) {
          done = true; window.removeEventListener("message", onMsg);
          resolve({ handled: true, json: d.json || {}, status: d.status, text: d.text, sentBytes: d.sentBytes, respHeaders: d.respHeaders });
        }
      };
      window.addEventListener("message", onMsg);
      window.postMessage({ __vbtt: "upload", id, url, crc, storageU: storageU || "", b64: bytesToB64(bytes) }, "*");
      // 60s: a relayed upload of a full-size slide can take longer than a few seconds on mobile data.
      setTimeout(() => { if (!done) { window.removeEventListener("message", onMsg); reject(new Error("no bridge")); } }, 60000);
    });
  }

  const uploadOk = (j) => j && (j.code === 2000 || (j.message && /success/i.test(j.message)));

  // Upload via XMLHttpRequest — this is what TikTok's own uploader uses; it
  // always sends a real Content-Length (fetch may chunk the body, which routes
  // the request to the CDN's file handler and yields an empty 204).
  function putXHR(url, headers, bytes) {
    return new Promise((resolve) => {
      const x = new XMLHttpRequest();
      x.open("POST", url, true);
      for (const k in headers) { try { x.setRequestHeader(k, headers[k]); } catch (_) {} }
      x.onload = () => { let j = {}; try { j = JSON.parse(x.responseText); } catch (_) {} resolve({ j, status: x.status, text: (x.responseText || "").slice(0, 200) }); };
      x.onerror = () => resolve({ j: {}, status: x.status || 0, text: "xhr network error" });
      x.send(bytes);
    });
  }

  async function putBytes(uploadHost, storeUri, bytes, storageU, auth) {
    if (!storageU) warn("no X-Storage-U (user id) resolved — upload may be rejected");
    const url = "https://" + uploadHost + "/upload/v1/" + storeUri;
    const crc = crc32Hex(bytes);
    const headers = {
      "Content-Type": "application/octet-stream",
      "Content-CRC32": crc,
      "Content-Disposition": 'attachment; filename="undefined"',
      ...(auth ? { "Authorization": auth } : {}),
      ...(storageU ? { "X-Storage-U": String(storageU) } : {}),
    };

    // 1) XHR from the page (matches TikTok's own uploader most closely).
    const xr = await putXHR(url, headers, bytes);
    if (uploadOk(xr.j)) { log("  ✓ upload ok (xhr)"); return; }
    log("  xhr upload non-2000: status " + xr.status + " body " + JSON.stringify(xr.text) + " — trying background…");

    // 2) Background worker fallback.
    const vb = await bridgeUpload(url, crc, storageU, bytes).catch((e) => ({ json: {}, status: "n/a", note: e && e.message }));
    if (uploadOk(vb.json)) { log("  ✓ upload ok (bg)"); return; }
    throw new Error("byte upload failed: xhr[" + xr.status + "] bg[" + vb.status + " sent " + vb.sentBytes + "B]");
  }

  async function commitImageUpload(creds, sessionKey) {
    const query = { Action: "CommitImageUpload", Version: "2018-08-01", ServiceId: SERVICE_ID };
    const body = JSON.stringify({ SessionKey: sessionKey });
    const payloadSha = await sha256Hex(new TextEncoder().encode(body));
    const { qs, headers } = await sigv4("POST", "/top/v1", query, payloadSha, creds);
    const r = await fetch(ORIGIN + "/top/v1?" + qs, {
      method: "POST",
      credentials: "include",
      headers: { "content-type": "application/json", ...headers },
      body,
    });
    const j = await r.json();
    const res = j.Result && (j.Result.PluginResult || j.Result.Results);
    if (!res || !res[0]) throw new Error("CommitImageUpload failed: " + JSON.stringify(j).slice(0, 300));
    const p = (j.Result.PluginResult && j.Result.PluginResult[0]) || {};
    return {
      uri: p.ImageUri || j.Result.Results[0].Uri,
      w: p.ImageWidth || 1080,
      h: p.ImageHeight || 1920,
    };
  }

  // Ask TikTok which sounds it would offer for this upload (the web "Add sound"
  // panel calls exactly this). zipUri = a StoreUri of an uploaded slide;
  // creation_id must match the one used to create the post. Returns [{id,title,
  // author,duration}]. The web library is smaller than the phone app's full
  // viral catalog, but every id here is attachable from web.
  async function recommendMusic(zipUri, creation_id, count) {
    const q = new URLSearchParams({
      zip_uri: zipUri,
      creation_id,
      count: String(count || 20),
      cursor: "0",
      content_type: "multi_photo",
      content_source: "upload",
      aid: "1988",
    });
    const r = await fetch(ORIGIN + "/aweme/v1/music/recommend/by/video/?" + q, {
      credentials: "include",
      headers: { accept: "application/json, text/plain, */*" },
    });
    let j = await r.json();
    // The recommend endpoint returns base64-wrapped JSON in some regions.
    if (typeof j === "string") { try { j = JSON.parse(atob(j)); } catch (_) {} }
    const raw = j.music_list || j.mc_list || j.music_info_list || j.music || [];
    const firstUrl = (o) => (o && o.url_list && o.url_list[0]) || "";
    return raw.map((it) => {
      const m = it.music || it.music_info || it;
      return {
        id: String(m.id_str || m.music_id || m.id || ""),
        title: m.title || m.music_name || "",
        author: m.author || m.owner_nickname || "",
        url: firstUrl(m.play_url) || m.music_url || "",
        album: firstUrl(m.cover_thumb) || firstUrl(m.cover_large) || firstUrl(m.cover_medium) || "",
        end: Number(m.audition_duration || m.duration || 60),
      };
    }).filter((m) => m.id && m.url);
  }

  // Build the text_extra hashtag index the post endpoint expects (UTF-16 offsets).
  function hashtagExtra(text) {
    const out = [];
    const re = /#([^\s#]+)/g;
    let m, i = 0;
    while ((m = re.exec(text))) {
      out.push({ tag_id: String(i++), start: m.index, end: m.index + m[0].length, user_id: "", type: 1, hashtag_name: m[1] });
    }
    return out;
  }

  // TikTok Studio's "Schedule" (verified against a real scheduled photo post, HAR): schedule_time is unix
  // seconds on a 5-minute boundary, 15 minutes to 30 days ahead (some accounts: 10 days), sent on the same
  // post request with tz_name; Studio first acknowledges scheduling once (post_schedule/ack, ack_type=2).
  const MIN_SCHEDULE_S = 15 * 60;
  const SCHEDULE_STEP_S = 5 * 60;

  const timeZone = () => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone || ""; } catch (_) { return ""; } };

  async function ackSchedule() {
    try {
      await fetch(ORIGIN + "/api/v1/post_schedule/ack/?" + new URLSearchParams({ ack_type: "2", aid: "1988" }), { method: "POST", credentials: "include" });
    } catch (e) { warn("schedule ack failed (continuing):", e && e.message); }
  }

  async function createPost({ title, caption, uploaded, creation_id, music, schedule }) {
    const images = uploaded.map((u, idx) => ({
      id: "file_" + Date.now() + "_" + idx,
      uri: u.uri,
      width_px: u.w,
      height_px: u.h,
    }));
    // Verified against a real add-sound photo post (HAR): the sound is a full
    // music_info object under single_post_feature_info, keyed music_id_string.
    const musicInfo = music && music.id && music.url ? {
      music_info: {
        music_id_string: String(music.id),
        music_title: music.title || "",
        music_author: music.author || "",
        music_url: music.url,
        music_begin_time: 0,
        music_end_time: Number(music.end) || 60,
        origin_volume: "100",
        music_volume: "100",
        music_album: music.album || "",
        music_type: 1,
      },
    } : {};
    const body = {
      feature_common_info_list: [
        {
          aigc_info: { aigc_label_type: 0 },
          org_game_post_use_store_region: false,
          privacy_setting_info: { allow_comment: 1, allow_duet: 1, allow_stitch: 1, allow_content_reuse: 1, allow_ai_remix: 2, visibility_type: 0 },
          draft_id: "",
          tcm_params: '{"commerce_toggle_info":{}}',
          anchors: [],
        },
      ],
      post_common_info: { creation_id: creation_id || creationId(), enter_post_page_from: 1, post_type: 4 },
      single_post_req_list: [
        {
          batch_index: 0,
          aweme_type: 150,
          image_post_content: { title: title || "", images, cover: images[0] },
          single_post_feature_info: {
            text: caption || "",
            text_extra: hashtagExtra(caption || ""),
            markup_text: caption || "",
            ...musicInfo,
          },
        },
      ],
    };
    let scheduleTime = 0;
    if (schedule) {
      // The upload can take a while: never send a time TikTok would refuse as too soon.
      scheduleTime = Math.max(Math.floor(Number(schedule)), Math.floor(Date.now() / 1000) + MIN_SCHEDULE_S + 60);
      scheduleTime = Math.ceil(scheduleTime / SCHEDULE_STEP_S) * SCHEDULE_STEP_S;
      // Same key order as TikTok Studio's own body.
      const f = body.feature_common_info_list[0];
      body.feature_common_info_list[0] = { aigc_info: f.aigc_info, org_game_post_use_store_region: f.org_game_post_use_store_region, schedule_time: scheduleTime, ...f };
      await ackSchedule();
    }
    const q = new URLSearchParams({ app_name: "tiktok_web", channel: "tiktok_web", device_platform: "web" });
    const tz = timeZone();
    if (tz) q.set("tz_name", tz);
    q.set("aid", "1988");
    // NOTE: msToken / X-Bogus / X-Gnarly are appended automatically by TikTok's
    // in-page webmssdk fetch hook — do NOT add them here.
    const r = await fetch(ORIGIN + "/tiktok/web/project/post/v1/?" + q, {
      method: "POST",
      credentials: "include",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    });
    const j = await r.json();
    if (j.status_code !== 0) throw new Error("create post failed: " + JSON.stringify(j).slice(0, 400));
    const item = j.single_post_resp_list && j.single_post_resp_list[0];
    return { projectId: j.project_id, itemId: item && item.item_id, scheduleTime };
  }

  // Steps 0-3: upload every slide to TikTok's image store (used by posts and drafts alike).
  async function uploadImages(images) {
    log("fetching upload credentials…");
    const creds = await getCreds();
    const uploaded = [];
    let storageU = null;
    let firstStoreUri = null;
    for (let i = 0; i < images.length; i++) {
      const bytes = await toBytes(images[i]);
      const { w, h } = await dimsOf(bytes);
      log(`image ${i + 1}/${images.length}: ${bytes.length} bytes, ${w}×${h} — applying…`);
      const ap = await applyImageUpload(creds, bytes.length);
      if (i === 0) firstStoreUri = ap.storeUri;
      if (!storageU) storageU = storageUserIdFromAuth(ap.auth);
      log(`  applied → host ${ap.uploadHost}, storageU ${storageU || "MISSING"} — uploading bytes…`);
      await putBytes(ap.uploadHost, ap.storeUri, bytes, storageU, ap.auth);
      log("  bytes uploaded — committing…");
      const committed = await commitImageUpload(creds, ap.sessionKey);
      log(`  ✓ ${committed.uri}`);
      uploaded.push(committed);
    }
    return { uploaded, firstStoreUri };
  }

  // ---- public API -----------------------------------------------------------

  // sound: falsy = silent (default, proven path); true = auto-pick the top
  // recommended web-library sound; a string = use that exact music_id.
  // schedule: unix seconds to publish at (TikTok Studio's Schedule); falsy = publish now.
  async function post({ title, caption, images, sound, schedule }) {
    if (!images || !images.length) throw new Error("no images");
    if (location.host !== "www.tiktok.com") warn("run this on www.tiktok.com while logged in");

    const creation_id = creationId();
    const { uploaded, firstStoreUri } = await uploadImages(images);

    // Optional: attach a web-library sound. recommendMusic returns full objects
    // (id/title/author/url/album/end); createPost turns one into music_info.
    let music = null;
    if (sound) {
      try {
        const sounds = await recommendMusic(firstStoreUri, creation_id);
        if (sounds.length) {
          music = (typeof sound === "string" && sounds.find((s) => s.id === sound)) || sounds[0];
          log(`  🎵 sound: ${music.title || music.id} by ${music.author || "?"}`);
        } else warn("no recommended sounds returned — posting silent");
      } catch (e) { warn("sound lookup failed — posting silent:", e && e.message); }
    }

    log("creating post…");
    const res = await createPost({ title, caption, uploaded, creation_id, music, schedule });
    log("%c✓ POSTED", "color:#12b886;font-weight:bold", res);
    if (!music) log("Posted silent. Pass sound:true to auto-attach a web-library sound, or add a viral sound in the app for max reach.");
    return res;
  }

  // Opens a file dialog so you can select the slide PNGs from disk, then posts.
  function postFromPicker({ title, caption, sound } = {}) {
    return new Promise((resolve, reject) => {
      const inp = document.createElement("input");
      inp.type = "file";
      inp.accept = "image/*";
      inp.multiple = true;
      inp.style.display = "none";
      document.body.appendChild(inp);
      inp.onchange = async () => {
        try {
          const files = [...inp.files].sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
          if (!files.length) throw new Error("no files selected");
          log("selected:", files.map((f) => f.name).join(", "));
          resolve(await post({ title, caption, images: files, sound }));
        } catch (e) { reject(e); } finally { inp.remove(); }
      };
      inp.click();
    });
  }

  // ---- drafts ----------------------------------------------------------------
  // TikTok Studio's "Save draft" (captured from a real HAR): the same image uploads as a post, then
  // POST /api/v1/draft/create_update/. The draft lands in TikTok Studio's drafts (tiktok.com/tiktokstudio/
  // content?tab=draft), where it can be opened, given a sound and posted. TikTok keeps drafts ~60 days.
  const DRAFT_SUPPORTED = true;

  // The caption box's rich-text state (Draft.js), one block per line, as Studio sends it.
  function markupText(text) {
    const blocks = String(text || "").split("\n").map((line) => ({
      key: rand(5), text: line, type: "unstyled", depth: 0, inlineStyleRanges: [], entityRanges: [], data: {},
    }));
    return JSON.stringify({ blocks, entityMap: {} });
  }

  async function saveDraft({ title, caption, images }) {
    if (!images || !images.length) throw new Error("no images");
    if (location.host !== "www.tiktok.com") warn("run this on www.tiktok.com while logged in");
    const { uploaded } = await uploadImages(images);
    const list = uploaded.map((u, idx) => ({ id: "file_" + Date.now() + "_" + idx, uri: u.uri, width_px: u.w, height_px: u.h }));
    const body = {
      draft: {
        web_feature_common_info: {
          aigc_info: { aigc_label_type: 0 },
          org_game_post_use_store_region: false,
          vedit_common_info: {},
          privacy_setting_info: { allow_comment: 1, allow_duet: 1, allow_stitch: 1, allow_content_reuse: 1, allow_ai_remix: 2, visibility_type: 0 },
          tcm_params: '{"commerce_toggle_info":{}}',
          anchors: [],
        },
        basic_info: {
          creation_id: creationId(),
          enter_post_page_from: 1,
          media_draft_info: { aweme_type: 150, image_post_content: { title: title || "", images: list, cover: list[0] } },
        },
        draft_id: "",
        web_video_param_list: [
          { single_post_feature_info: { text: caption || "", text_extra: hashtagExtra(caption || ""), markup_text: markupText(caption) } },
        ],
      },
    };
    log("saving draft…");
    // msToken / X-Bogus / X-Gnarly are added by TikTok's in-page fetch hook, as for posts.
    const r = await fetch(ORIGIN + "/api/v1/draft/create_update/?aid=1988", {
      method: "POST",
      credentials: "include",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    });
    const j = await r.json();
    const draftId = j && j.draft && j.draft.draft && j.draft.draft.draft_id;
    if (j.status_code !== 0 || !draftId) throw new Error("save draft failed: " + JSON.stringify(j).slice(0, 400));
    log("%c✓ SAVED TO DRAFTS", "color:#12b886;font-weight:bold", draftId);
    return { draftId };
  }

  // ---- Slidemill jobs (sent by bridge.js) -------------------------------------
  function currentUsername() {
    try {
      const el = document.getElementById("__UNIVERSAL_DATA_FOR_REHYDRATION__");
      const data = el ? JSON.parse(el.textContent) : window.__UNIVERSAL_DATA_FOR_REHYDRATION__;
      const user = data && data.__DEFAULT_SCOPE__ && data.__DEFAULT_SCOPE__["webapp.app-context"] &&
        data.__DEFAULT_SCOPE__["webapp.app-context"].user;
      return (user && (user.uniqueId || user.unique_id)) || "";
    } catch (_) { return ""; }
  }

  let jobRunning = false;
  window.addEventListener("message", async (ev) => {
    if (ev.source !== window) return;
    const d = ev.data;
    if (!d || !d.__vbtt) return;
    if (d.__vbtt === "hello") {
      window.postMessage({ __vbtt: "helloResult", id: d.id, result: { ready: true, username: currentUsername(), draft: DRAFT_SUPPORTED } }, "*");
      return;
    }
    if (d.__vbtt !== "job") return;
    const job = d.job || {};
    let result;
    if (jobRunning) {
      result = { ok: false, error: "another post is still running in this tab" };
    } else {
      jobRunning = true;
      try {
        const run = job.action === "draft" ? saveDraft : post;
        log(`Slidemill job ${job.id}: ${job.action || "publish"} "${job.title}" (${(job.images || []).length} slides)`);
        const res = await run({ title: job.title, caption: job.caption, images: job.images, sound: job.sound, schedule: job.schedule });
        result = { ok: true, itemId: (res && res.itemId) || "", draftId: (res && res.draftId) || "", scheduleTime: (res && res.scheduleTime) || 0, username: currentUsername() };
      } catch (e) {
        result = { ok: false, error: String((e && e.message) || e), unsupported: !!(e && e.unsupported), username: currentUsername() };
      } finally {
        jobRunning = false;
      }
    }
    window.postMessage({ __vbtt: "jobResult", id: d.id, result }, "*");
  });

  window.VBTikTok = { post, saveDraft, postFromPicker, recommendMusic, _internals: { getCreds, applyImageUpload, commitImageUpload, sigv4, hashtagExtra, crc32Hex } };
  log("ready. Call: await VBTikTok.postFromPicker({ title, caption })");
})();
