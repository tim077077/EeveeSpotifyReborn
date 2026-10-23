/*
 * Slidemill TikTok Poster — page <-> background bridge (ISOLATED world, so it can use chrome.*).
 *
 * - "upload": the MAIN-world injector posts image bytes; we relay them to the background worker
 *   (which can reach the CDN without CORS/CSP) and post the result back. (Original behavior.)
 * - Slidemill jobs: the background asks this tab to say hello or to post a job; we forward that
 *   to the MAIN world (where TikTok's request signing lives) and return the answer. Job results
 *   are also saved to chrome.storage so they survive the background worker going to sleep.
 */
window.addEventListener("message", (ev) => {
  if (ev.source !== window) return;
  const d = ev.data;
  if (!d || d.__vbtt !== "upload") return;
  try {
    chrome.runtime.sendMessage(
      { type: "vbUpload", url: d.url, crc: d.crc, storageU: d.storageU, b64: d.b64 },
      (resp) => {
        const json = (resp && resp.json) || (resp && resp.error ? { message: "bg error: " + resp.error } : {});
        window.postMessage({ __vbtt: "uploadResult", id: d.id, json, status: resp && resp.status, text: resp && resp.text, sentBytes: resp && resp.sentBytes, respHeaders: resp && resp.respHeaders }, "*");
      }
    );
  } catch (e) {
    window.postMessage({ __vbtt: "uploadResult", id: d.id, json: { message: "bridge error: " + (e && e.message) } }, "*");
  }
});

// Ask the MAIN-world script something and wait for its answer (null on timeout).
function askPage(kind, payload, timeoutMs) {
  return new Promise((resolve) => {
    const id = "sm" + Math.random().toString(36).slice(2);
    const onMsg = (ev) => {
      if (ev.source !== window) return;
      const d = ev.data;
      if (d && d.__vbtt === kind + "Result" && d.id === id) {
        window.removeEventListener("message", onMsg);
        resolve(d.result);
      }
    };
    window.addEventListener("message", onMsg);
    window.postMessage({ __vbtt: kind, id, ...payload }, "*");
    setTimeout(() => { window.removeEventListener("message", onMsg); resolve(null); }, timeoutMs);
  });
}

const showStatus = (text) => window.postMessage({ __vbtt: "status", text }, "*");

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (!msg) return;
  if (msg.type === "slidemillHello") {
    askPage("hello", {}, 5000).then(sendResponse);
    return true;
  }
  if (msg.type === "slidemillJob") {
    // Pings keep the background worker awake while TikTok uploads run (can take a minute).
    const keepAlive = setInterval(() => chrome.runtime.sendMessage({ type: "slidemillKeepAlive" }).catch(() => {}), 10000);
    askPage("job", { job: msg.job }, 11 * 60 * 1000)
      .then(async (result) => {
        result = result || { ok: false, error: "the poster in this tab didn't finish in time" };
        await chrome.storage.local.set({ ["result:" + msg.job.id]: result });
        sendResponse(result);
      })
      .finally(() => clearInterval(keepAlive));
    return true;
  }
  if (msg.type === "slidemillStatus") showStatus(msg.text);
});

// Show the current queue status in the on-page panel when the tab opens.
chrome.runtime.sendMessage({ type: "slidemillGetStatus" }).then((s) => { if (s && s.text) showStatus(s.text); }).catch(() => {});
