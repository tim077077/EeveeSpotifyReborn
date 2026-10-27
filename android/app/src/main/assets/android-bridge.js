/*
 * Slidemill Android bridge: plays the role of the extension's bridge.js inside the app's WebView.
 * - "upload": tiktok-inject.js asks for a CDN byte upload; the app does it natively
 *   (SlidemillNative.upload) and posts the result back as "uploadResult".
 * - The app asks the page "hello"/"job" through window.__slidemillAsk and receives the answers
 *   (helloResult/jobResult/listMineResult) via SlidemillNative.onResult.
 */
(function () {
  if (window.__slidemillAndroid) return;
  window.__slidemillAndroid = true;
  window.addEventListener("message", function (ev) {
    if (ev.source !== window) return;
    var d = ev.data;
    if (!d || !d.__vbtt) return;
    if (d.__vbtt === "upload") {
      SlidemillNative.upload(String(d.id), String(d.url), String(d.crc), String(d.storageU || ""), String(d.b64));
    } else if ((d.__vbtt === "helloResult" || d.__vbtt === "jobResult" || d.__vbtt === "listMineResult") && String(d.id).indexOf("and") === 0) {
      SlidemillNative.onResult(String(d.id), JSON.stringify(d.result || null));
    }
  });
  window.__slidemillAsk = function (kind, id, payloadJson) {
    var msg = JSON.parse(payloadJson || "{}");
    msg.__vbtt = kind;
    msg.id = id;
    window.postMessage(msg, "*");
  };
})();
