/*
 * Slidemill TikTok Poster — floating panel (from the original VintedBot poster) (MAIN world, calls window.VBTikTok).
 * Two modes:
 *   • Pick slides & post  — one carousel: choose the slide-*.png, type title+caption.
 *   • Post a folder       — batch: choose your out/ folder; every subfolder that
 *                           has slide-*.png + caption.txt is posted in turn, with
 *                           a pause between (TikTok rate-limits rapid posting).
 * Titles/captions for batch come from each folder's title.txt / caption.txt.
 */
(function () {
  "use strict";
  if (window.__vbTikTokPanel) return;
  window.__vbTikTokPanel = true;

  const LS = "vb_tt_panel";
  const load = () => { try { return JSON.parse(localStorage.getItem(LS) || "{}"); } catch (_) { return {}; } };
  const save = (o) => { try { localStorage.setItem(LS, JSON.stringify(o)); } catch (_) {} };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  function waitForApi(cb, tries) {
    if (window.VBTikTok && window.VBTikTok.post) return cb();
    if ((tries || 0) > 40) return;
    setTimeout(() => waitForApi(cb, (tries || 0) + 1), 250);
  }

  // Group a webkitdirectory FileList into carousels keyed by their folder path.
  function groupCarousels(files) {
    const byDir = {};
    for (const f of files) {
      const rel = f.webkitRelativePath || f.name;
      const dir = rel.split("/").slice(0, -1).join("/");
      (byDir[dir] = byDir[dir] || []).push(f);
    }
    const out = [];
    for (const dir of Object.keys(byDir)) {
      const fs = byDir[dir];
      const slides = fs.filter((f) => /(^|\/)slide-\d+\.png$/i.test(f.webkitRelativePath || f.name))
        .sort((a, b) => (a.name.match(/\d+/) - b.name.match(/\d+/)));
      if (!slides.length) continue;
      const capF = fs.find((f) => /(^|\/)caption\.txt$/i.test(f.webkitRelativePath || f.name));
      const titleF = fs.find((f) => /(^|\/)title\.txt$/i.test(f.webkitRelativePath || f.name));
      out.push({ dir, slides, capF, titleF, name: dir.split("/").pop() });
    }
    return out.sort((a, b) => a.dir.localeCompare(b.dir, undefined, { numeric: true }));
  }

  function build() {
    const saved = load();
    const wrap = document.createElement("div");
    wrap.id = "vb-tt-panel";
    wrap.innerHTML = `
      <style>
        #vb-tt-panel{position:fixed;right:16px;bottom:16px;z-index:2147483647;width:340px;
          font:13px/1.4 -apple-system,Segoe UI,Roboto,sans-serif;color:#111;
          background:#fff;border:1px solid #e5e0d8;border-radius:14px;
          box-shadow:0 10px 30px rgba(0,0,0,.18);overflow:hidden}
        #vb-tt-panel *{box-sizing:border-box}
        #vb-tt-h{display:flex;align-items:center;gap:8px;padding:10px 12px;background:#fe2c55;color:#fff}
        #vb-tt-h b{font-size:13px;font-weight:700;letter-spacing:.2px;flex:1}
        #vb-tt-min{cursor:pointer;opacity:.9;font-size:16px;line-height:1;padding:0 4px}
        #vb-tt-body{padding:12px;display:grid;gap:8px}
        #vb-tt-panel label{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.4px;color:#8a8378}
        #vb-tt-panel input[type=text],#vb-tt-panel textarea{width:100%;padding:8px 9px;border:1px solid #ddd6cc;
          border-radius:9px;font:13px/1.4 inherit;color:#111;background:#faf8f4;resize:vertical}
        #vb-tt-cap{min-height:84px}
        #vb-tt-panel button{appearance:none;border:0;border-radius:10px;padding:11px;color:#fff;font-weight:700;font-size:13px;cursor:pointer}
        #vb-tt-go{background:#fe2c55}
        #vb-tt-batch{background:#0b0b0d}
        #vb-tt-panel button:disabled{opacity:.55;cursor:default}
        #vb-tt-msg{font-size:12px;min-height:16px;white-space:pre-wrap;color:#555}
        #vb-tt-msg.ok{color:#12833a}#vb-tt-msg.err{color:#c0341b}
        #vb-tt-list{font-size:12px;max-height:150px;overflow:auto;display:grid;gap:3px}
        #vb-tt-list div{display:flex;gap:6px}#vb-tt-list .s{width:16px;text-align:center}
        #vb-tt-note{font-size:11px;color:#a29a8c}
        .vb-hr{height:1px;background:#eee6da;margin:2px 0}
        #vb-tt-auto{padding:7px 12px;font-size:12px;background:#fff4f6;border-bottom:1px solid #f3dbe0;color:#6b2734}
        @media (prefers-color-scheme:dark){
          #vb-tt-panel{background:#161514;border-color:#332f2a;color:#f2ede6}
          #vb-tt-panel input[type=text],#vb-tt-panel textarea{background:#211f1d;border-color:#3a352f;color:#f2ede6}
          #vb-tt-msg{color:#bdb5a8}.vb-hr{background:#2c2823}
          #vb-tt-auto{background:#2a1a1e;border-color:#3a2329;color:#f3c4ce}
        }
      </style>
      <div id="vb-tt-h"><b>Slidemill · Post to TikTok</b><span id="vb-tt-min">–</span></div>
      <div id="vb-tt-auto" title="Slidemill queue status">Slidemill: starting…</div>
      <div id="vb-tt-body">
        <div><label>Title (on the carousel)</label>
          <input id="vb-tt-title" type="text" placeholder="3 reasons you're not making money on Vinted"></div>
        <div><label>Caption + hashtags</label>
          <textarea id="vb-tt-cap" placeholder="👀 Save this…&#10;&#10;#vinted #reselling #depop"></textarea></div>
        <label style="display:flex;align-items:center;gap:7px;text-transform:none;letter-spacing:0;color:#111;font-weight:400;font-size:12px;cursor:pointer">
          <input id="vb-tt-sound" type="checkbox" style="width:auto;margin:0"> Add a sound (TikTok web library — smaller than the app's, but real)</label>
        <button id="vb-tt-go">Pick slides &amp; post (one)</button>
        <div class="vb-hr"></div>
        <label>Batch · post your whole out/ folder</label>
        <div style="display:flex;gap:8px;align-items:center">
          <span style="font-size:11px;color:#a29a8c">gap between posts</span>
          <input id="vb-tt-gap" type="text" value="30" style="width:56px" title="seconds between posts"> <span style="font-size:11px;color:#a29a8c">sec</span>
        </div>
        <button id="vb-tt-batch">Pick a folder &amp; post all</button>
        <div id="vb-tt-list"></div>
        <div id="vb-tt-msg"></div>
        <div id="vb-tt-note">Sound = TikTok's web library (smaller than the app's viral catalog). For a specific trending sound, add it in the app. ToS/ban risk — use a throwaway.</div>
      </div>`;
    document.documentElement.appendChild(wrap);

    const $ = (id) => wrap.querySelector(id);
    const title = $("#vb-tt-title"), cap = $("#vb-tt-cap"), go = $("#vb-tt-go"), batch = $("#vb-tt-batch"),
      gap = $("#vb-tt-gap"), msg = $("#vb-tt-msg"), list = $("#vb-tt-list"), body = $("#vb-tt-body"), sound = $("#vb-tt-sound");
    title.value = saved.title || ""; cap.value = saved.caption || ""; if (saved.gap) gap.value = saved.gap;
    sound.checked = !!saved.sound;
    const persist = () => save({ title: title.value, caption: cap.value, gap: gap.value, sound: sound.checked });
    title.oninput = persist; cap.oninput = persist; gap.oninput = persist; sound.onchange = persist;
    $("#vb-tt-min").onclick = () => { body.hidden = !body.hidden; };
    // Queue status from the Slidemill extension (relayed by bridge.js).
    const autoLine = $("#vb-tt-auto");
    window.addEventListener("message", (ev) => {
      if (ev.source === window && ev.data && ev.data.__vbtt === "status") autoLine.textContent = "Slidemill: " + ev.data.text;
    });

    // ---- single ----
    go.onclick = async () => {
      msg.className = ""; msg.textContent = "Opening file picker…"; go.disabled = true; batch.disabled = true;
      try {
        const res = await window.VBTikTok.postFromPicker({ title: title.value.trim(), caption: cap.value, sound: sound.checked });
        msg.className = "ok"; msg.textContent = "✓ Posted! item_id " + (res && res.itemId ? res.itemId : "(check profile)");
      } catch (e) { msg.className = "err"; msg.textContent = "✗ " + ((e && e.message) || e); }
      finally { go.disabled = false; batch.disabled = false; }
    };

    // ---- batch ----
    batch.onclick = () => {
      const inp = document.createElement("input");
      inp.type = "file"; inp.multiple = true; inp.webkitdirectory = true; inp.style.display = "none";
      document.body.appendChild(inp);
      inp.onchange = async () => {
        const carousels = groupCarousels([...inp.files]); inp.remove();
        list.innerHTML = ""; msg.className = "";
        if (!carousels.length) { msg.className = "err"; msg.textContent = "No carousels found (need slide-*.png inside subfolders)."; return; }
        const rows = carousels.map((c) => { const d = document.createElement("div"); d.innerHTML = '<span class="s">•</span><span>' + c.name + "</span>"; list.appendChild(d); return d.querySelector(".s"); });
        const gapMs = Math.max(8, parseInt(gap.value) || 30) * 1000;
        msg.textContent = "Posting " + carousels.length + " carousels…"; go.disabled = true; batch.disabled = true;
        let done = 0;
        for (let i = 0; i < carousels.length; i++) {
          const c = carousels[i]; rows[i].textContent = "⏳";
          try {
            const caption = c.capF ? await c.capF.text() : "";
            const ttl = (c.titleF ? await c.titleF.text() : "").trim() || c.name;
            await window.VBTikTok.post({ title: ttl, caption: caption.trim(), images: c.slides, sound: sound.checked });
            rows[i].textContent = "✓"; done++;
          } catch (e) { rows[i].textContent = "✗"; rows[i].title = (e && e.message) || String(e); }
          if (i < carousels.length - 1) { const wait = Math.round(gapMs * (0.8 + Math.random() * 0.5)); msg.textContent = "Posted " + done + "/" + carousels.length + " — waiting " + Math.round(wait / 1000) + "s before next…"; await sleep(wait); }
        }
        msg.className = done === carousels.length ? "ok" : "err";
        msg.textContent = "Done: " + done + "/" + carousels.length + " posted." + (done < carousels.length ? " Hover a ✗ for its error." : "");
        go.disabled = false; batch.disabled = false;
      };
      inp.click();
    };
  }

  waitForApi(build);
})();
