/*
 * Slidemill TikTok Poster — background service worker.
 *
 * 1. Byte uploads (unchanged from the original poster): uploads image bytes to TikTok's CDN.
 *    Extension background fetches with host_permissions aren't subject to the page's CSP or to
 *    CORS, which is what blocks a direct in-page upload. bridge.js relays the bytes here. The CDN
 *    rejects uploads without Origin/Referer of www.tiktok.com, and a background fetch omits Origin
 *    (JS can't set it), so a declarativeNetRequest rule stamps them on.
 *
 * 2. Slidemill queue: every minute, if a logged-in tiktok.com tab is open, ask Slidemill for the
 *    next due post, download its slides, hand them to the tab to post (or save as a draft), and
 *    report the result. Slidemill enforces the daily cap and the gap between posts.
 */
const VERSION = chrome.runtime.getManifest().version;
const DEFAULTS = { server: "http://localhost:8000", key: "", enabled: true, openTab: false };
const BUSY_TIMEOUT_MS = 15 * 60 * 1000;

// ---- 1. CDN upload relay ---------------------------------------------------------------

const DNR_RULE = {
  id: 1,
  priority: 1,
  action: {
    type: "modifyHeaders",
    requestHeaders: [
      { header: "origin", operation: "set", value: "https://www.tiktok.com" },
      { header: "referer", operation: "set", value: "https://www.tiktok.com/" },
    ],
  },
  condition: {
    urlFilter: "/upload/v1/",
    requestDomains: ["tiktokcdn-eu.com", "tiktokcdn.com", "byteimg.com"],
    resourceTypes: ["xmlhttprequest", "other"],
  },
};
async function ensureRule() {
  try {
    await chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [1], addRules: [DNR_RULE] });
  } catch (e) { console.warn("[Slidemill bg] could not set header rule:", e); }
}

async function relayUpload(msg) {
  await ensureRule(); // make sure the Origin/Referer rule is active before we upload
  const bin = atob(msg.b64);
  const u = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
  const r = await fetch(msg.url, {
    method: "POST",
    headers: {
      "Content-Type": "application/octet-stream",
      "Content-CRC32": msg.crc,
      "Content-Disposition": 'attachment; filename="undefined"',
      ...(msg.storageU ? { "X-Storage-U": String(msg.storageU) } : {}),
    },
    body: u,
  });
  const text = await r.text().catch(() => "");
  let json = {};
  try { json = JSON.parse(text); } catch (_) {}
  const hdrs = {};
  r.headers.forEach((v, k) => { hdrs[k] = v; });
  return { json, status: r.status, text: text.slice(0, 300), sentBytes: u.length, respHeaders: hdrs };
}

// ---- 2. Slidemill queue ------------------------------------------------------------------

async function config() {
  return { ...DEFAULTS, ...(await chrome.storage.local.get(Object.keys(DEFAULTS))) };
}

async function api(path, options = {}) {
  const c = await config();
  const res = await fetch(c.server.replace(/\/+$/, "") + "/api/v1" + path, {
    ...options,
    headers: { Authorization: "Bearer " + c.key, "Content-Type": "application/json", ...(options.headers || {}) },
  });
  if (!res.ok) throw new Error("Slidemill answered " + res.status + (res.status === 401 ? " (wrong password?)" : ""));
  return res.json();
}

async function setStatus(text) {
  await chrome.storage.session.set({ status: { text, at: Date.now() } });
  const tabs = await chrome.tabs.query({ url: "https://www.tiktok.com/*" }).catch(() => []);
  for (const t of tabs) chrome.tabs.sendMessage(t.id, { type: "slidemillStatus", text }).catch(() => {});
}

function sendToTab(tabId, message, timeoutMs) {
  return Promise.race([
    chrome.tabs.sendMessage(tabId, message).catch(() => null),
    new Promise((resolve) => setTimeout(() => resolve(null), timeoutMs)),
  ]);
}

function waitForTabLoad(tabId) {
  return new Promise((resolve) => {
    const done = () => { chrome.tabs.onUpdated.removeListener(listener); resolve(); };
    const listener = (id, info) => { if (id === tabId && info.status === "complete") done(); };
    chrome.tabs.onUpdated.addListener(listener);
    setTimeout(done, 30000);
  });
}

async function tiktokTab(c) {
  const tabs = await chrome.tabs.query({ url: "https://www.tiktok.com/*" });
  if (tabs.length) return tabs.find((t) => t.active) || tabs[0];
  if (!c.openTab) return null;
  const tab = await chrome.tabs.create({ url: "https://www.tiktok.com/", active: false, pinned: true });
  await waitForTabLoad(tab.id);
  await new Promise((r) => setTimeout(r, 4000)); // let content scripts start
  return tab;
}

const bytesToB64 = (bytes) => {
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(bin);
};

async function fetchAsDataUrl(url) {
  const c = await config();
  const res = await fetch(url, { headers: { Authorization: "Bearer " + c.key } });
  if (!res.ok) throw new Error("couldn't download slide " + url + " (" + res.status + ")");
  const type = res.headers.get("content-type") || "image/jpeg";
  return "data:" + type + ";base64," + bytesToB64(new Uint8Array(await res.arrayBuffer()));
}

// Results are saved by bridge.js too, in case this worker was asleep when the post finished.
async function flushSavedResults() {
  const all = await chrome.storage.local.get(null);
  for (const key of Object.keys(all)) {
    if (!key.startsWith("result:")) continue;
    const postId = key.slice("result:".length);
    try { await reportResult(postId, all[key]); } catch (e) { console.warn("[Slidemill bg] report failed", e); continue; }
    await chrome.storage.local.remove(key);
  }
}

async function reportResult(postId, r) {
  await api("/extension/jobs/" + postId + "/result", {
    method: "POST",
    body: JSON.stringify({
      ok: !!(r && r.ok),
      item_id: (r && r.itemId) || "",
      username: (r && r.username) || "",
      error: (r && r.error) || (r ? "" : "the tiktok.com tab didn't answer"),
      unsupported: !!(r && r.unsupported),
    }),
  }).catch((e) => { if (!String(e.message).includes("404")) throw e; }); // 404: already recorded
}

let polling = false;
async function poll() {
  if (polling) return;
  polling = true;
  try {
    const c = await config();
    if (!c.enabled) return setStatus("Paused (turn it on in the extension popup)");
    if (!c.key) return setStatus("Not connected — open the extension popup and enter your Slidemill password");
    await flushSavedResults();

    const { busy } = await chrome.storage.session.get("busy");
    if (busy && Date.now() - busy.since < BUSY_TIMEOUT_MS) return;

    const tab = await tiktokTab(c);
    if (!tab) return setStatus("Waiting for a logged-in tiktok.com tab");
    const hello = await sendToTab(tab.id, { type: "slidemillHello" }, 6000);
    if (!hello || !hello.ready) return setStatus("Reload your tiktok.com tab so the poster can start");

    const q = new URLSearchParams({ version: VERSION, username: hello.username || "", draft: hello.draft ? "1" : "0" });
    const res = await api("/extension/next?" + q);
    if (!res.job) return setStatus("Connected · " + res.reason);

    const job = res.job;
    await chrome.storage.session.set({ busy: { postId: job.id, since: Date.now() } });
    await setStatus((job.action === "draft" ? "Saving draft: " : "Posting: ") + job.title);
    let result;
    try {
      const images = [];
      for (const url of job.images) images.push(await fetchAsDataUrl(url));
      result = await sendToTab(tab.id, { type: "slidemillJob", job: { ...job, images } }, 12 * 60 * 1000);
    } catch (e) {
      result = { ok: false, error: String((e && e.message) || e) };
    }
    if (result === null) {
      // No answer yet (slow upload, or the tab went away). Don't report a failure: that would
      // re-queue a post that may still succeed and get it posted twice. bridge.js saves the real
      // result for the next poll; if none ever arrives, Slidemill releases the claim after 20 min.
      return setStatus("Still waiting for the tiktok.com tab to finish: " + job.title);
    }
    await reportResult(job.id, result);
    await chrome.storage.local.remove("result:" + job.id);
    await setStatus(
      result && result.ok ? (job.action === "draft" ? "✓ Saved draft: " : "✓ Posted: ") + job.title
        : "✗ " + ((result && result.error) || "no answer from the tiktok.com tab"),
    );
  } catch (e) {
    await setStatus("✗ " + ((e && e.message) || e));
  } finally {
    await chrome.storage.session.remove("busy");
    polling = false;
  }
}

// ---- wiring ------------------------------------------------------------------------------

chrome.runtime.onInstalled.addListener(() => { ensureRule(); chrome.alarms.create("slidemill-poll", { periodInMinutes: 1 }); });
chrome.runtime.onStartup.addListener(() => { ensureRule(); chrome.alarms.create("slidemill-poll", { periodInMinutes: 1 }); });
ensureRule();
chrome.alarms.onAlarm.addListener((a) => { if (a.name === "slidemill-poll") poll(); });

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (!msg) return;
  if (msg.type === "vbUpload") {
    relayUpload(msg).then(sendResponse, (e) => sendResponse({ error: String((e && e.message) || e) }));
    return true; // async sendResponse
  }
  if (msg.type === "slidemillKeepAlive") return; // receiving it keeps this worker awake during a post
  if (msg.type === "slidemillPollNow") { poll().then(() => sendResponse({ ok: true })); return true; }
  if (msg.type === "slidemillGetStatus") {
    chrome.storage.session.get("status").then(({ status }) => sendResponse(status || null));
    return true;
  }
});
