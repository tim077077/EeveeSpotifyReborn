// Slidemill dashboard: hash-routed vanilla JS over /api/v1.

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const view = $("#view");

const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

function storedKey() {
  try { return localStorage.getItem("slidemill_key") || ""; } catch { return ""; }
}
function saveKey(k) {
  try { localStorage.setItem("slidemill_key", k); } catch { /* private mode */ }
}

async function api(path, { method = "GET", body, form } = {}) {
  const headers = {};
  const key = storedKey();
  if (key) headers.Authorization = `Bearer ${key}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(`/api/v1${path}`, {
    method,
    headers,
    body: form ?? (body !== undefined ? JSON.stringify(body) : undefined),
  });
  if (res.status === 401) {
    const k = prompt("API key (SLIDEMILL_API_KEY):");
    if (k) { saveKey(k); return api(path, { method, body, form }); }
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const d = data.detail ?? data;
    throw new Error(d?.error || (typeof d === "string" ? d : JSON.stringify(d)));
  }
  return data;
}

let toastTimer;
function toast(msg, error = false) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = "toast" + (error ? " error" : "");
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), 4500);
}

async function busy(btn, fn) {
  btn.disabled = true;
  const label = btn.innerHTML;
  btn.innerHTML = `<span class="spinner"></span> ${label}`;
  try { return await fn(); }
  catch (e) { toast(e.message, true); }
  finally { btn.disabled = false; btn.innerHTML = label; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- phone app mode ------------------------------------------------------------------
// The Android app runs this same dashboard against its built-in engine (/status.platform ==
// "android"). There is no TikTok developer app there: posts go out from the app's TikTok tab, or
// are handed to the TikTok app to finish.
let PHONE = false;
const APP = window.SlidemillApp; // native bridge, only inside the Android app
const PHONE_MODES = {
  publish: "Post by itself from this phone (TikTok tab)",
  draft: "Send to the TikTok app at that time (you add a sound, post)",
};
function phoneModes(sel) {
  if (!PHONE || !sel) return;
  [...sel.options].forEach((o) => {
    const action = o.value.toLowerCase().startsWith("browser") ? o.value.split(":")[1] || "publish" : null;
    if (action) o.textContent = PHONE_MODES[action];
    else o.remove();
  });
}
function hideOnPhone(...els) {
  if (PHONE) els.filter(Boolean).forEach((el) => (el.closest("div") || el).setAttribute("hidden", ""));
}
async function detectPlatform() {
  try { PHONE = (await api("/status")).platform === "android"; } catch { /* stays false */ }
  if (PHONE) {
    document.documentElement.classList.add("phone");
    $$('nav a[data-view="accounts"]').forEach((a) => a.remove());
  }
}

const OPTIONS = {
  font: ["tiktok", "bebas", "anton", "archivo", "cormorant", "cormorant_italic", "playfair_italic"],
  font_size: ["extra_extra_small", "extra_small", "small", "medium", "large", "extra_large"],
  text_style: ["outline", "text", "background", "light_background"],
  text_color: ["white", "black", "yellow", "red", "pink", "orange", "green", "blue", "purple"],
};
const select = (name, values, current, attrs = "") =>
  `<select name="${name}" ${attrs}>${values
    .map((v) => `<option value="${esc(v)}" ${v === current ? "selected" : ""}>${esc(v)}</option>`)
    .join("")}</select>`;

// ---- cron <-> posting times ---------------------------------------------------------

function timesToCron(times, days) {
  return times
    .split(",")
    .map((t) => t.trim())
    .filter(Boolean)
    .map((t) => {
      const [h, m = "0"] = t.split(":");
      return `${parseInt(m, 10)} ${parseInt(h, 10)} * * ${days || "*"}`;
    });
}
function cronToTimes(crons) {
  const simple = crons.every((c) => /^\d+ \d+ \* \* [\d,\-*]+$/.test(c));
  // Per-day schedules (different days per time) can't be shown as one times list.
  if (!simple || new Set(crons.map((c) => c.split(" ")[4])).size > 1) return null;
  const times = crons.map((c) => {
    const [m, h] = c.split(" ");
    return `${h.padStart(2, "0")}:${m.padStart(2, "0")}`;
  });
  return { times: times.join(", "), days: crons[0]?.split(" ")[4] ?? "*" };
}

// ---- views ---------------------------------------------------------------------------

const views = {};

views.home = async () => {
  const [st, autos, shows, posts, accounts] = await Promise.all([
    api("/status"), api("/automations"), api("/slideshows?limit=8"), api("/posts?days=30"), api("/tiktok/accounts"),
  ]);
  const steps = PHONE ? [
    [st.ai, "Add an AI key in Settings (Claude, OpenAI, Gemini, OpenRouter, Groq, …)"],
    [st.poster?.logged_in, "Log in to TikTok in the TikTok tab"],
    [st.poster?.auto_post, "Turn on auto-posting in the Phone tab"],
    [autos.automations.length > 0, "Create your first automation (or use Studio)"],
  ] : [
    [st.ai, "Add an AI key in Settings (Claude, OpenAI, Gemini, OpenRouter, Groq, …)"],
    [st.tiktok, "Set TIKTOK_CLIENT_KEY / TIKTOK_CLIENT_SECRET"],
    [accounts.accounts.length > 0, "Connect a TikTok account"],
    [autos.automations.length > 0, "Create your first automation"],
  ];
  const s = posts.statistics;
  view.innerHTML = `
    <h1>Dashboard</h1>
    <p class="sub">Last 30 days${PHONE ? "" : " across all accounts"}.</p>
    <div class="grid">
      <div class="card"><div class="muted small">Views</div><div class="stat">${s.views.toLocaleString()}</div></div>
      <div class="card"><div class="muted small">Likes</div><div class="stat">${s.likes.toLocaleString()}</div></div>
      <div class="card"><div class="muted small">Posts</div><div class="stat">${s.total_posts}</div></div>
      <div class="card"><div class="muted small">Live automations</div><div class="stat">${autos.automations.filter((a) => !a.paused && a.schedule.length).length}</div></div>
    </div>
    ${steps.some(([ok]) => !ok) ? `<h2>Setup</h2><div class="card">${steps
      .map(([ok, text]) => `<div>${ok ? "✅" : "⬜"} ${esc(text)}</div>`).join("")}</div>` : ""}
    <h2>Recent slideshows</h2>
    ${showGrid(shows.slideshows)}
  `;
};

function showGrid(list) {
  if (!list.length) return `<p class="muted">Nothing yet. <a href="#create">Create a slideshow</a>.</p>`;
  return `<div class="grid thumbs">${list
    .map(
      (s) => `<div class="card show-card" onclick="location.hash='#slideshow/${s.id}'">
        ${s.slides[0] ? `<img src="${esc(s.slides[0])}" loading="lazy" alt="">` : `<div class="placeholder">${s.status === "failed" ? "failed" : '<span class="spinner"></span>'}</div>`}
        <div class="title">${esc(s.title)}</div>
        <div class="row small muted"><span class="pill ${s.status === "completed" ? "ok" : s.status === "failed" ? "bad" : ""}">${s.status}</span>
        ${s.posts.length ? `<span class="pill live">${esc(s.posts[0].status)}</span>` : ""}
        ${s.prediction ? `<span class="pill ${scoreClass(s.prediction.viral_potential)}" title="Predicted viral potential">🔥 ${s.prediction.viral_potential}</span>` : ""}</div>
      </div>`,
    )
    .join("")}</div>`;
}

views.create = async () => {
  const autos = await api("/automations");
  view.innerHTML = `
    <h1>Create slideshow</h1>
    <p class="sub">Describe the slideshow. Include topic, slide count, tone, fonts or text placement if you care.</p>
    <div class="card">
      <label>Prompt</label>
      <textarea id="prompt" placeholder="7 slides: morning habits that actually helped my focus. Casual, first person. Hook in big white outline text, body text in white background boxes at the top."></textarea>
      <div class="form-grid">
        <div><label>Aspect ratio</label>${select("aspect", ["4:5", "9:16", "1:1", "16:9"], "4:5", 'id="aspect"')}</div>
        <div><label>Language</label><input id="language" value="English"></div>
        <div><label>Use automation's style & images (optional)</label>
          <select id="automation"><option value="">None</option>${autos.automations
            .map((a) => `<option value="${a.id}">${esc(a.title)}</option>`).join("")}</select></div>
        <div><label>Backgrounds</label>${select("imgsrc", ["auto", "ai", "stock"], "auto", 'id="imgsrc"')}</div>
        <div><label>Background image URLs, comma-separated (optional, by slide order)</label><input id="images"></div>
      </div>
      <div class="row" style="margin-top:16px"><button class="primary" id="go">Generate</button><span class="muted small">Takes ~30-60s.</span></div>
    </div>
    <div id="result"></div>`;
  $("#go").onclick = (e) =>
    busy(e.currentTarget, async () => {
      const body = {
        prompt: $("#prompt").value.trim(),
        aspect_ratio: $("#aspect").value,
        language: $("#language").value || "English",
        automation_id: $("#automation").value || null,
        image_source: $("#imgsrc").value,
        images: $("#images").value.split(",").map((s) => s.trim()).filter(Boolean),
      };
      if (!body.prompt) throw new Error("Write a prompt first.");
      const { slideshow_id } = await api("/slideshows/generate", { method: "POST", body });
      $("#result").innerHTML = `<p class="muted"><span class="spinner"></span> Writing slides and sourcing images…</p>`;
      for (;;) {
        await sleep(3000);
        const st = await api(`/slideshows/${slideshow_id}/status`);
        if (st.status === "failed") throw new Error(st.error || "Generation failed");
        if (st.status === "completed") break;
      }
      location.hash = `#slideshow/${slideshow_id}`;
    });
};

views.slideshow = async (id) => {
  const [s, accounts] = await Promise.all([api(`/slideshows/${id}`), api("/tiktok/accounts")]);
  const spec = s.spec || { slides: [] };
  view.innerHTML = `
    <div class="row"><h1 class="grow">${esc(s.title)}</h1>
      <span class="pill ${s.status === "completed" ? "ok" : "bad"}">${s.status}</span></div>
    <p class="sub">${esc(s.prompt)}</p>
    ${s.error ? `<div class="card" style="border-color:var(--accent)">${esc(s.error)}</div>` : ""}
    ${s.status === "generating" || s.status === "rendering" ? `<p><span class="spinner"></span> Working… this page refreshes.</p>` : ""}
    <div class="slides">${s.slides.map((u) => `<a href="${esc(u)}" target="_blank"><img src="${esc(u)}?v=${Date.now()}" alt=""></a>`).join("")}</div>
    ${s.status === "completed" ? `<div class="card" id="predict-card" style="margin-bottom:14px">${predictionHtml(s.prediction)}</div>` : ""}

    <div class="form-grid">
      <div class="card">
        <h3>Post</h3>
        <label>Caption (TikTok title, max 89)</label><input id="caption" maxlength="89" value="${esc(s.caption)}">
        <label>Description</label><textarea id="description">${esc(s.description)}</textarea>
        <label>Account</label><select id="acct">${accounts.accounts
          .map((a) => `<option value="${a.id}">@${esc(a.username || a.display_name)}</option>`).join("") || "<option value=''>No accounts connected</option>"}</select>
        <label>How</label><select id="mode">
          <option value="BROWSER:publish">Post from my browser (extension)</option>
          <option value="BROWSER:draft">Save as draft from my browser (extension)</option>
          <option value="MEDIA_UPLOAD">Send to my TikTok drafts (official API)</option>
          <option value="DIRECT_POST">Post now (official API)</option></select>
        <label class="check" id="sound-row"><input type="checkbox" id="sound" checked> Add a sound from TikTok's web library</label>
        <div id="direct-opts"></div>
        <div class="row"><button class="primary" id="publish">Send to TikTok</button><button id="save-meta">Save caption</button></div>
        ${s.posts.length ? `<h3 style="margin-top:16px">History</h3>${s.posts
          .map((p) => `<div class="small">${esc(p.created_at.slice(0, 16))} · ${esc(PHONE ? (p.action === "draft" ? "TikTok app" : "phone") : p.post_mode)} · <b>${esc(p.status)}</b>
            ${p.scheduled_for && p.status === "queued" ? `· for ${esc(new Date(p.scheduled_for).toLocaleString())}` : ""} ${p.error ? `· ${esc(p.error)}` : ""}
            ${PHONE && p.action === "draft" && ["queued", "ready_to_share"].includes(p.status) ? `<button class="small" data-share="${p.id}">Open in TikTok</button>` : ""}</div>`).join("")}` : ""}
      </div>
      <div class="card">
        <h3>Export</h3>
        <div class="row">
          ${s.slides.map((u, i) => `<a class="btn small" href="${esc(u)}" download>Slide ${i + 1}</a>`).join("")}
        </div>
        <div class="row" style="margin-top:12px"><button id="video">Export MP4</button>
          ${s.video_url ? `<a class="btn" href="${esc(s.video_url)}" target="_blank">Open video</a>` : ""}</div>
        <div class="row" style="margin-top:12px"><button class="danger" id="delete">Delete slideshow</button></div>
      </div>
    </div>

    <h2>Edit slides</h2>
    <div class="card">
      <div class="form-grid">
        <div><label>Aspect ratio</label>${select("aspect_ratio", ["4:5", "9:16", "1:1", "16:9"], spec.aspect_ratio)}</div>
        <div><label>Default text position</label>${select("text_position", ["top", "center", "bottom"], spec.text_position)}</div>
        <div><label>Darken overlay opacity (%)</label><input type="number" name="background_opacity" min="0" max="100" value="${spec.background_opacity ?? 20}"></div>
        <div><label>&nbsp;</label>
          <label class="check"><input type="checkbox" name="overlay_hook" ${spec.overlay_hook ? "checked" : ""}> Overlay on hook</label>
          <label class="check"><input type="checkbox" name="overlay_body" ${spec.overlay_body ? "checked" : ""}> Overlay on body</label>
          <label class="check"><input type="checkbox" name="keep_original_aspect_ratio" ${spec.keep_original_aspect_ratio ? "checked" : ""}> Keep original image ratio</label>
        </div>
      </div>
      <div id="slide-editors">${spec.slides.map((sl, i) => slideEditor(sl, i, s.slides[i])).join("")}</div>
      <button class="primary" id="rerender">Save & re-render</button>
    </div>`;

  if (s.status === "generating" || s.status === "rendering") {
    setTimeout(() => location.hash === `#slideshow/${id}` && route(), 4000);
  }

  wirePrediction(id, spec);
  phoneModes($("#mode"));
  if (PHONE) [$("#acct"), $("#acct").previousElementSibling].forEach((el) => (el.hidden = true));
  view.onclick = async (ev) => {
    const b = ev.target.closest("[data-share]");
    if (!b) return;
    try { await api(`/posts/${b.dataset.share}/share`, { method: "POST" }); route(); } catch (err) { toast(err.message, true); }
  };
  $("#publish").onclick = (e) =>
    busy(e.currentTarget, async () => {
      const [mode, browserAction] = $("#mode").value.split(":");
      if (mode !== "BROWSER" && !$("#acct").value) throw new Error("Connect a TikTok account first.");
      const extra = mode === "DIRECT_POST" ? readConsent($("#consent-direct")) : {};
      const p = await api(`/slideshows/${id}/publish`, {
        method: "POST",
        body: {
          tiktok_account_id: $("#acct").value || null, post_mode: mode, caption: $("#caption").value, description: $("#description").value,
          browser_action: browserAction || "publish", sound: $("#sound").checked, ...extra,
        },
      });
      if (PHONE && p.action === "draft") {
        await api(`/posts/${p.id}/share`, { method: "POST" });
        toast("Opening TikTok. The caption is copied: paste it, add a sound and post.");
        return route();
      }
      toast(
        p.status === "failed" ? `Failed: ${p.error}`
          : PHONE ? "Queued. This phone posts it within a minute (auto-posting must be on in the Phone tab)."
          : p.post_mode === "BROWSER" ? "Queued. Your browser extension picks it up within a minute (keep tiktok.com open)."
          : p.post_mode === "DIRECT_POST" ? "Posted. It can take a few minutes to appear on your profile."
          : "Sent to your TikTok drafts — open TikTok's inbox to finish it.",
        p.status === "failed",
      );
      route();
    });
  const refreshDirect = async () => {
    const box = $("#direct-opts");
    const m = $("#mode").value;
    $("#publish").textContent = PHONE ? (m === "BROWSER:draft" ? "Open in TikTok" : "Post from this phone")
      : m === "DIRECT_POST" ? "Post to TikTok" : m.startsWith("BROWSER") ? "Queue for my browser" : "Send to drafts";
    $("#publish").disabled = false;
    $("#sound-row").hidden = !m.startsWith("BROWSER");
    if (m === "BROWSER:draft" && PHONE) {
      box.innerHTML = `<p class="small muted">Opens TikTok's editor with these slides. The caption is copied to your clipboard.</p>`;
      return;
    }
    if (m === "BROWSER:draft") {
      box.innerHTML = `<p class="small" style="color:var(--warn)">Browser drafts aren't wired up yet: it waits in the queue (never posted publicly) until they are.</p>`;
      return;
    }
    if (m !== "DIRECT_POST" || !$("#acct").value) { box.innerHTML = ""; return; }
    box.innerHTML = `<p class="muted small"><span class="spinner"></span> Checking the account…</p>`;
    try {
      const creator = await api(`/tiktok/accounts/${$("#acct").value}/creator-info`);
      box.innerHTML = consentBlock("consent-direct", { creator });
      wireConsent($("#consent-direct"), $("#publish"));
      box.insertAdjacentHTML("beforeend", `<p class="small muted">Preview: the slides above are exactly what will be posted. Nothing is sent until you press Post.</p>`);
    } catch (err) {
      box.innerHTML = `<p class="small" style="color:var(--warn)">Can't post right now: ${esc(err.message)}</p>`;
    }
  };
  $("#mode").onchange = refreshDirect;
  $("#acct").onchange = refreshDirect;
  refreshDirect();
  $("#save-meta").onclick = (e) =>
    busy(e.currentTarget, async () => {
      await api(`/slideshows/${id}`, { method: "PUT", body: { caption: $("#caption").value, description: $("#description").value } });
      toast("Saved");
    });
  $("#video").onclick = (e) =>
    busy(e.currentTarget, async () => {
      const r = await api(`/slideshows/${id}/video`, { method: "POST" });
      window.open(r.video_url, "_blank");
      route();
    });
  $("#delete").onclick = async () => {
    if (!confirm("Delete this slideshow?")) return;
    await api(`/slideshows/${id}`, { method: "DELETE" });
    location.hash = "#slideshows";
  };
  $("#slide-editors").onclick = (ev) => {
    const add = ev.target.closest("[data-add-text]");
    if (add) {
      const box = add.previousElementSibling;
      box.insertAdjacentHTML("beforeend", textItemEditor({ text: "", font: "tiktok", font_size: "medium", text_style: "outline", text_color: "white" }));
    }
    const rm = ev.target.closest("[data-remove-text]");
    if (rm) rm.closest(".text-item").remove();
  };
  $("#rerender").onclick = (e) =>
    busy(e.currentTarget, async () => {
      const card = e.currentTarget.closest(".card");
      const next = {
        ...spec,
        aspect_ratio: $("[name=aspect_ratio]", card).value,
        text_position: $("[name=text_position]", card).value,
        background_opacity: +$("[name=background_opacity]", card).value,
        overlay_hook: $("[name=overlay_hook]", card).checked,
        overlay_body: $("[name=overlay_body]", card).checked,
        keep_original_aspect_ratio: $("[name=keep_original_aspect_ratio]", card).checked,
        slides: $$(".slide-edit", card).map((el, i) => ({
          ...spec.slides[i],
          image: $("[name=image]", el).value || null,
          text_position: $("[name=slide_pos]", el).value || null,
          text_items: $$(".text-item", el)
            .map((ti) => ({
              ...(spec.slides[i].text_items[+ti.dataset.idx] || {}),
              text: $("[name=text]", ti).value,
              font: $("[name=font]", ti).value,
              font_size: $("[name=font_size]", ti).value,
              text_style: $("[name=text_style]", ti).value,
              text_color: $("[name=text_color]", ti).value,
            }))
            .filter((t) => t.text.trim()),
        })),
      };
      await api(`/slideshows/${id}`, { method: "PUT", body: { spec: next } });
      toast("Re-rendered");
      route();
    });
};

function textItemEditor(t, idx = -1) {
  return `<div class="text-item" data-idx="${idx}">
    <input name="text" value="${esc(t.text)}" placeholder="Text">
    ${select("font", OPTIONS.font, t.font)}${select("font_size", OPTIONS.font_size, t.font_size)}
    ${select("text_style", OPTIONS.text_style, t.text_style)}
    <div class="row" style="flex-wrap:nowrap">${select("text_color", OPTIONS.text_color, t.text_color)}<button type="button" data-remove-text title="Remove">✕</button></div>
  </div>`;
}

function slideEditor(sl, i, url) {
  return `<div class="slide-edit">
    <div>${url ? `<img src="${esc(url)}?v=${Date.now()}" alt="">` : ""}<div class="small muted">#${i + 1} · ${esc(sl.role)}</div></div>
    <div>
      <div>${sl.text_items.map((t, j) => textItemEditor(t, j)).join("")}</div>
      <button type="button" class="small" data-add-text>+ text</button>
      <div class="form-grid">
        <div><label>Background (media path or URL)</label><input name="image" value="${esc(sl.image || "")}"></div>
        <div><label>Text position override</label>${select("slide_pos", ["", "top", "center", "bottom"], sl.text_position || "")}</div>
      </div>
    </div>
  </div>`;
}

views.slideshows = async () => {
  const data = await api("/slideshows?limit=100");
  view.innerHTML = `<div class="row"><h1 class="grow">Slideshows</h1><a class="btn" href="#create">New</a></div>
    <p class="sub">${data.total} total.</p>${showGrid(data.slideshows)}`;
};

views.automations = async () => {
  const [data, accounts] = await Promise.all([api("/automations"), api("/tiktok/accounts")]);
  const acct = Object.fromEntries(accounts.accounts.map((a) => [a.id, a]));
  view.innerHTML = `
    <div class="row"><h1 class="grow">Automations</h1><button class="primary" id="new">New automation</button></div>
    <p class="sub">Each automation writes a fresh slideshow on schedule from its hooks and style, then posts it.</p>
    <div class="grid">${data.automations
      .map(
        (a) => `<div class="card">
        <div class="row"><h3 class="grow">${a.favorite ? "★ " : ""}${esc(a.title)}</h3>
          <span class="pill ${a.paused ? "bad" : a.schedule.length ? "live" : ""}">${a.paused ? "paused" : a.schedule.length ? "live" : "no schedule"}</span></div>
        <div class="small muted">${acct[a.tiktok_account_id] ? "@" + esc(acct[a.tiktok_account_id].username) : "no account"} · ${a.hooks.length} hooks · ${a.schedule.length} posts/day</div>
        <div class="small muted">${a.target_country ? `🎯 ${esc(a.target_country)} · ` : ""}times in ${esc(a.timezone)} · ${esc(PHONE ? (a.post_settings.post_mode === "BROWSER" && a.post_settings.browser_action !== "draft" ? "posts from this phone" : "hands off to the TikTok app")
          : { DIRECT_POST: "auto-publish (API)", BROWSER: a.post_settings.browser_action === "draft" ? "browser drafts" : "browser posting" }[a.post_settings.post_mode] || "drafts (API)")}</div>
        <div class="small muted">Next post: ${a.next_runs[0] ? esc(new Date(a.next_runs[0]).toLocaleString()) : "—"}</div>
        <div class="row" style="margin-top:12px">
          <button data-run="${a.id}">Run now</button>
          <button data-edit="${a.id}">Edit</button>
          <button data-toggle="${a.id}" data-paused="${a.paused}">${a.paused ? "Resume" : "Pause"}</button>
          <a class="btn" href="#slideshows-of/${a.id}">Slideshows</a>
        </div></div>`,
      )
      .join("") || `<p class="muted">No automations yet.</p>`}</div>`;
  $("#new").onclick = () => automationDialog(null);
  view.onclick = async (ev) => {
    const b = ev.target.closest("button");
    if (!b) return;
    if (b.dataset.edit) automationDialog(data.automations.find((a) => a.id === b.dataset.edit));
    if (b.dataset.run)
      busy(b, async () => {
        await api(`/automations/${b.dataset.run}/run`, { method: "POST", body: { mode: "export" } });
        toast("Running. The slideshow appears under Slideshows in about a minute.");
      });
    if (b.dataset.toggle)
      busy(b, async () => {
        await api(`/automations/${b.dataset.toggle}`, { method: "PATCH", body: { paused: b.dataset.paused !== "true" } });
        route();
      });
  };
};

views["slideshows-of"] = async (id) => {
  const [a, data] = await Promise.all([api(`/automations/${id}`), api(`/slideshows?automation_id=${id}&limit=100`)]);
  view.innerHTML = `<h1>${esc(a.title)}</h1><p class="sub">${data.total} slideshows.</p>${showGrid(data.slideshows)}`;
};

async function automationDialog(a) {
  const [accounts, products, collections] = await Promise.all([api("/tiktok/accounts"), api("/products"), api("/collections")]);
  const ps = a?.post_settings ?? {};
  const is = a?.image_settings ?? {};
  const sched = cronToTimes(a?.schedule.map((s) => s.cron) ?? []) ?? { times: "", days: "*", simple: false };
  if (sched.simple === undefined) sched.simple = true;
  const opt = (list, cur, label) =>
    `<option value="">${label}</option>` + list.map((x) => `<option value="${x.id}" ${x.id === cur ? "selected" : ""}>${esc(x.name || x.username || x.display_name)}</option>`).join("");
  const dlg = $("#dialog");
  $("#dialog-body").innerHTML = `
    <h2 style="margin-top:0">${a ? "Edit" : "New"} automation</h2>
    <div class="form-grid">
      <div><label>Title</label><input name="title" value="${esc(a?.title ?? "")}" required></div>
      <div><label>TikTok account</label><select name="tiktok_account_id">${opt(accounts.accounts, a?.tiktok_account_id, "— none —")}</select></div>
      <div><label>Product (soft CTA)</label><select name="product_id">${opt(products.products, a?.product_id, "— none —")}</select></div>
      <div><label>Language</label><input name="language" value="${esc(a?.language ?? "English")}"></div>
    </div>
    <label>Hooks / topics — one per line, rotated each run</label>
    <textarea name="hooks" placeholder="5 habits that quietly fixed my sleep&#10;things I stopped buying in my 20s">${esc((a?.hooks ?? []).join("\n"))}</textarea>
    <label>Style (creative direction for Claude)</label>
    <textarea name="style" placeholder="6 slides. Casual first-person. Hook big white outline text; body slides black text on white boxes at the top.">${esc(a?.style ?? "")}</textarea>
    <div class="form-grid">
      <div><label>Posting times (HH:MM, comma-separated)</label><input name="times" value="${esc(sched.times)}" placeholder="09:00, 18:30"></div>
      <div><label>Days (cron day-of-week: * = every day, 1-5 = weekdays)</label><input name="days" value="${esc(sched.days)}"></div>
      <div><label>Timezone for these times (IANA, blank = server)</label><input name="timezone" value="${esc(a?.timezone ?? "")}" placeholder="Europe/London"></div>
      <div><label>&nbsp;</label><a class="btn" href="#timing">Pick best times by country →</a></div>
    </div>
    ${a && !sched.simple ? `<p class="small muted">This automation uses a per-day schedule (${a.schedule.length} slots${a.target_country ? ` for ${esc(a.target_country)}` : ""}). Leave the times field empty to keep it.</p>` : ""}
    <label>Pre-test before posting: if a new slideshow's predicted viral potential is below this, write one more version and keep the better one (0 = off)</label>
    <input type="number" name="min_viral_score" min="0" max="100" value="${a?.min_viral_score ?? 0}" style="max-width:120px">
    <h3 style="margin-top:18px">Posting</h3>
    <div class="form-grid">
      <div><label>Mode</label><select name="post_mode">
        <option value="MEDIA_UPLOAD" ${ps.post_mode !== "DIRECT_POST" ? "selected" : ""}>Send to my TikTok drafts</option>
        <option value="DIRECT_POST" ${ps.post_mode === "DIRECT_POST" ? "selected" : ""}>Post by itself (official API)</option>
        <option value="BROWSER:publish" ${ps.post_mode === "BROWSER" && ps.browser_action !== "draft" ? "selected" : ""}>Post from my browser (extension)</option>
        <option value="BROWSER:draft" ${ps.post_mode === "BROWSER" && ps.browser_action === "draft" ? "selected" : ""}>Save drafts from my browser (extension)</option></select></div>
      <div></div>
      <div><label>Caption</label>${select("caption_mode", ["prompt", "static"], ps.caption_mode ?? "prompt")}<input name="caption_static" placeholder="Static caption" value="${esc(ps.caption_static ?? "")}"></div>
      <div><label>Description</label>${select("description_mode", ["prompt", "static"], ps.description_mode ?? "prompt")}<input name="description_static" placeholder="Static description" value="${esc(ps.description_static ?? "")}"></div>
    </div>
    <label class="check"><input type="checkbox" name="auto_post" ${ps.auto_post ?? true ? "checked" : ""}> Auto-post after generating</label>
    <label class="check"><input type="checkbox" name="auto_music" ${ps.auto_music ?? true ? "checked" : ""}> Let TikTok add music (direct post)</label>
    <div id="auto-consent"></div>
    <label class="check"><input type="checkbox" name="disclose_ai_content" ${ps.disclose_ai_content ?? true ? "checked" : ""}> Label as AI-generated</label>
    <h3 style="margin-top:18px">Images</h3>
    <div class="form-grid">
      <div><label>Source</label>${select("source", ["auto", "ai", "stock", "collection"], is.source ?? "auto")}</div>
      <div><label>Body collection</label><select name="collection_id">${opt(collections.collections, is.collection_id, "— none —")}</select></div>
      <div><label>Hook (first slide) collection</label><select name="hook_collection_id">${opt(collections.collections, is.hook_collection_id, "— same as body —")}</select></div>
      <div><label>CTA collection</label><select name="cta_collection_id">${opt(collections.collections, is.cta_collection_id, "— none —")}</select></div>
      <div><label>Aspect ratio</label>${select("aspect_ratio", ["4:5", "9:16", "1:1", "16:9"], is.aspect_ratio ?? "4:5")}</div>
      <div><label>Overlay opacity %</label><input type="number" name="background_opacity" min="0" max="100" value="${is.background_opacity ?? 30}"></div>
    </div>
    <label>Visual style for backgrounds (used by AI images)</label>
    <input name="visual_style" value="${esc(is.visual_style ?? "")}" placeholder="moody film photos, warm tungsten light, cozy apartment details">
    <label class="check"><input type="checkbox" name="overlay_hook" ${is.overlay_hook ? "checked" : ""}> Darken hook image</label>
    <label class="check"><input type="checkbox" name="overlay_body" ${is.overlay_body ? "checked" : ""}> Darken body images</label>
    <label class="check"><input type="checkbox" name="text_on_first_slide_only" ${is.text_on_first_slide_only ? "checked" : ""}> Text on first slide only</label>
    <label class="check"><input type="checkbox" name="cta_slide" ${is.cta_slide ? "checked" : ""}> Append a CTA slide from the CTA collection</label>
    <label class="check"><input type="checkbox" name="keep_original_aspect_ratio" ${is.keep_original_aspect_ratio ? "checked" : ""}> Keep original image ratio</label>
    <div class="row" style="margin-top:18px">
      <button class="primary" value="save" id="save-auto">Save</button>
      <button value="cancel" formnovalidate>Cancel</button>
      ${a ? `<button class="danger" value="delete" id="del-auto" formnovalidate>Delete</button>` : ""}
    </div>`;
  dlg.showModal();
  if (PHONE) {
    const f0 = $("#dialog-body");
    const pm = f0.elements.post_mode;
    if (ps.post_mode !== "BROWSER") pm.value = ps.post_mode === "DIRECT_POST" ? "BROWSER:publish" : "BROWSER:draft";
    phoneModes(pm);
    hideOnPhone(f0.elements.tiktok_account_id);
    f0.elements.auto_music.closest("label").lastChild.textContent = " Add a sound from TikTok's library";
    f0.elements.timezone.placeholder = "blank = this phone's timezone";
  }
  const showAutoConsent = () => {
    const direct = $("#dialog-body").elements.post_mode.value === "DIRECT_POST";
    $("#auto-consent").innerHTML = direct ? consentBlock("consent-auto", { saved: ps, automatic: true }) : "";
    if (direct) wireConsent($("#consent-auto"), $("#save-auto"));
  };
  $("#dialog-body").elements.post_mode.onchange = showAutoConsent;
  showAutoConsent();
  const f = $("#dialog-body");
  const val = (n) => f.elements[n].value;
  const chk = (n) => f.elements[n].checked;
  $("#save-auto").onclick = (e) => {
    e.preventDefault();
    busy(e.currentTarget, async () => {
      const body = {
        title: val("title") || "Untitled automation",
        tiktok_account_id: val("tiktok_account_id") || null,
        product_id: val("product_id") || null,
        language: val("language") || "English",
        hooks: val("hooks").split("\n").map((s) => s.trim()).filter(Boolean),
        style: val("style"),
        timezone: val("timezone").trim(),
        min_viral_score: Math.max(0, Math.min(100, +val("min_viral_score") || 0)),
        ...(val("times").trim() || !a || sched.simple ? { schedule: timesToCron(val("times"), val("days").trim()) } : {}),
        post_settings: {
          post_mode: val("post_mode").split(":")[0],
          browser_action: val("post_mode").split(":")[1] || "publish",
          ...(val("post_mode") === "DIRECT_POST"
            ? (({ privacy_level, allow_comments, brand_organic, branded_content }) => ({
                visibility: privacy_level, allow_comments, brand_organic, branded_content, consented: true,
              }))(readConsent($("#consent-auto")))
            : { visibility: ps.visibility ?? "SELF_ONLY", allow_comments: ps.allow_comments ?? false, consented: ps.consented ?? false }),
          caption_mode: val("caption_mode"), caption_static: val("caption_static"),
          description_mode: val("description_mode"), description_static: val("description_static"),
          auto_post: chk("auto_post"), auto_music: chk("auto_music"),
          disclose_ai_content: chk("disclose_ai_content"),
        },
        image_settings: {
          source: val("source"), visual_style: val("visual_style"), collection_id: val("collection_id") || null,
          hook_collection_id: val("hook_collection_id") || null, cta_collection_id: val("cta_collection_id") || null,
          aspect_ratio: val("aspect_ratio"), background_opacity: +val("background_opacity"),
          overlay_hook: chk("overlay_hook"), overlay_body: chk("overlay_body"),
          text_on_first_slide_only: chk("text_on_first_slide_only"), cta_slide: chk("cta_slide"),
          keep_original_aspect_ratio: chk("keep_original_aspect_ratio"),
        },
      };
      if (a) await api(`/automations/${a.id}`, { method: "PATCH", body });
      else await api("/automations", { method: "POST", body });
      dlg.close();
      toast("Saved");
      route();
    });
  };
  const del = $("#del-auto");
  if (del)
    del.onclick = async (e) => {
      e.preventDefault();
      if (!confirm("Delete this automation and its schedule?")) return;
      await api(`/automations/${a.id}`, { method: "DELETE" });
      dlg.close();
      route();
    };
}

views.collections = async () => {
  const data = await api("/collections");
  view.innerHTML = `
    <h1>Image collections</h1>
    <p class="sub">Backgrounds for automations. Upload your own or import from stock photos. Images are used least-used first, so a collection cycles before repeating.</p>
    <div class="card row"><input id="cname" class="grow" placeholder="New collection name"><button class="primary" id="cadd">Create</button></div>
    <div class="grid" style="margin-top:14px">${data.collections
      .map((c) => `<a class="card" href="#collection/${c.id}" style="color:inherit;text-decoration:none"><h3>${esc(c.name)}</h3><div class="muted small">${c.image_count} images</div></a>`)
      .join("")}</div>`;
  $("#cadd").onclick = (e) =>
    busy(e.currentTarget, async () => {
      const name = $("#cname").value.trim();
      if (!name) throw new Error("Name it first.");
      const c = await api("/collections", { method: "POST", body: { name } });
      location.hash = `#collection/${c.id}`;
    });
};

views.collection = async (id) => {
  const [cols, imgs] = await Promise.all([api("/collections"), api(`/collections/${id}/images`)]);
  const c = cols.collections.find((x) => x.id === id) ?? { name: "Collection" };
  view.innerHTML = `
    <div class="row"><h1 class="grow">${esc(c.name)}</h1><button class="danger" id="cdel">Delete collection</button></div>
    <p class="sub">${imgs.images.length} images.</p>
    <div class="form-grid">
      <div class="card"><h3>Upload</h3><input type="file" id="files" multiple accept="image/*"><div class="row" style="margin-top:10px"><button class="primary" id="up">Upload</button></div></div>
      <div class="card"><h3>Import stock photos</h3><div class="row"><input id="q" class="grow" placeholder="aesthetic bedroom night lamp"><input id="n" type="number" value="12" min="1" max="40" style="width:80px"></div>
        <div class="row" style="margin-top:10px"><button class="primary" id="imp">Import</button></div></div>
    </div>
    <div class="img-grid" style="margin-top:16px">${imgs.images
      .map((i) => `<figure><img src="${esc(i.url)}" loading="lazy" alt=""><button data-del="${i.id}">✕</button></figure>`).join("")}</div>`;
  $("#up").onclick = (e) =>
    busy(e.currentTarget, async () => {
      const files = $("#files").files;
      if (!files.length) throw new Error("Pick files first.");
      const form = new FormData();
      [...files].forEach((f) => form.append("files", f));
      await api(`/collections/${id}/upload`, { method: "POST", form });
      route();
    });
  $("#imp").onclick = (e) =>
    busy(e.currentTarget, async () => {
      await api(`/collections/${id}/import`, { method: "POST", body: { query: $("#q").value, count: +$("#n").value } });
      route();
    });
  $("#cdel").onclick = async () => {
    if (!confirm("Delete the collection and its images?")) return;
    await api(`/collections/${id}`, { method: "DELETE" });
    location.hash = "#collections";
  };
  $(".img-grid").onclick = async (ev) => {
    const b = ev.target.closest("[data-del]");
    if (!b) return;
    await api(`/images/${b.dataset.del}`, { method: "DELETE" });
    b.closest("figure").remove();
  };
};

views.products = async () => {
  const data = await api("/products");
  view.innerHTML = `
    <h1>Products</h1>
    <p class="sub">Attach a product to an automation and Claude works it in as a soft call-to-action.</p>
    <div class="card">
      <div class="form-grid"><div><label>Name</label><input id="pname"></div><div><label>URL</label><input id="purl"></div></div>
      <label>What it is / why people like it</label><textarea id="pdesc"></textarea>
      <button class="primary" id="padd" style="margin-top:10px">Add product</button>
    </div>
    <div class="grid" style="margin-top:14px">${data.products
      .map((p) => `<div class="card"><h3>${esc(p.name)}</h3><div class="small muted">${esc(p.description)}</div>
        <button class="danger small" data-pdel="${p.id}" style="margin-top:10px">Delete</button></div>`).join("")}</div>`;
  $("#padd").onclick = (e) =>
    busy(e.currentTarget, async () => {
      await api("/products", { method: "POST", body: { name: $("#pname").value, url: $("#purl").value, description: $("#pdesc").value } });
      route();
    });
  view.onclick = async (ev) => {
    const b = ev.target.closest("[data-pdel]");
    if (b) { await api(`/products/${b.dataset.pdel}`, { method: "DELETE" }); route(); }
  };
};

views.accounts = async () => {
  const data = await api("/tiktok/accounts");
  const err = new URLSearchParams(location.hash.split("?")[1] || "").get("error");
  view.innerHTML = `
    <h1>TikTok accounts</h1>
    <p class="sub">Connect as many as you like. To add another, log out of tiktok.com in this browser first, then connect again.</p>
    ${err ? `<div class="card" style="border-color:var(--accent)">${esc(err)}</div>` : ""}
    <button class="primary" id="connect">Connect TikTok</button>
    <div class="grid" style="margin-top:14px">${data.accounts
      .map((a) => `<div class="card row">${a.avatar_url ? `<img src="${esc(a.avatar_url)}" width="40" height="40" style="border-radius:50%">` : ""}
        <div class="grow"><b>@${esc(a.username)}</b><div class="small muted">${esc(a.display_name)}</div></div>
        <button class="danger small" data-adel="${a.id}">Remove</button></div>`).join("")}</div>`;
  $("#connect").onclick = (e) =>
    busy(e.currentTarget, async () => {
      const { url } = await api("/tiktok/connect");
      location.href = url;
    });
  view.onclick = async (ev) => {
    const b = ev.target.closest("[data-adel]");
    if (b && confirm("Disconnect this account?")) { await api(`/tiktok/accounts/${b.dataset.adel}`, { method: "DELETE" }); route(); }
  };
};

views.analytics = async () => {
  const sort = new URLSearchParams(location.hash.split("?")[1] || "").get("sort") || "recent";
  const [data, cal] = await Promise.all([api(`/posts?days=90&sort=${sort}&limit=200`), api("/virality/calibration").catch(() => null)]);
  const s = data.statistics;
  view.innerHTML = `
    <div class="row"><h1 class="grow">Analytics</h1><button id="refresh">Refresh from TikTok</button></div>
    <p class="sub">Last 90 days. Metrics sync every 10 minutes for published posts.</p>
    <div class="grid">
      ${["views", "likes", "comments", "shares"].map((k) => `<div class="card"><div class="muted small">${k}</div><div class="stat">${s[k].toLocaleString()}</div></div>`).join("")}
    </div>
    ${cal ? `<div class="card" style="margin-top:14px"><h3>Does the virality prediction work for you?</h3>
      <p class="small">${esc(cal.summary)}</p>
      <p class="small muted">Compares each posted slideshow's predicted score with its real views (rank correlation over ${cal.posts} posts).
      Higgsfield-style predictors publish no such check; this one grades itself on your account.</p></div>` : ""}
    <h2>Posts</h2>
    <div class="row small">Sort: ${["recent", "views", "likes", "shares"].map((k) => `<a href="#analytics?sort=${k}" class="pill ${k === sort ? "live" : ""}">${k}</a>`).join("")}</div>
    <div class="card" style="margin-top:10px;overflow-x:auto"><table>
      <tr><th></th><th>Slideshow</th><th>Status</th><th>Views</th><th>Likes</th><th>Comments</th><th>Shares</th><th>Posted</th></tr>
      ${data.posts.map((p) => `<tr onclick="location.hash='#slideshow/${p.slideshow_id}'" style="cursor:pointer">
        <td>${p.thumb ? `<img src="${esc(p.thumb)}" alt="">` : ""}</td><td>${esc(p.title)}</td><td>${esc(p.status)}</td>
        <td>${p.views.toLocaleString()}</td><td>${p.likes.toLocaleString()}</td><td>${p.comments}</td><td>${p.shares}</td>
        <td class="small muted">${esc(p.created_at.slice(0, 16).replace("T", " "))}</td></tr>`).join("")}
    </table></div>`;
  $("#refresh").onclick = (e) =>
    busy(e.currentTarget, async () => {
      await api("/posts/refresh", { method: "POST" });
      toast("Refreshing… reload in a moment.");
    });
};

views.studio = async () => {
  const [st, accounts, products] = await Promise.all([api("/status"), api("/tiktok/accounts"), api("/products")]);
  view.innerHTML = `
    <h1>Studio</h1>
    <p class="sub">Describe the kind of slideshows you want. It plans the format, writes every slide, makes the images and renders them.</p>
    ${st.ai ? "" : `<div class="card" style="border-color:var(--accent)">Add an AI key in <a href="#settings">Settings</a> first — any provider works.</div>`}
    <div class="card">
      <label>What do you want to make?</label>
      <textarea id="brief" style="min-height:120px" placeholder="Glow-up slideshows for girls 16-22: realistic self-care habits, aesthetic soft pink vibes, first person, 6 slides each, text in white boxes at the top."></textarea>
      <div class="form-grid">
        <div><label>How many slideshows</label><input id="count" type="number" min="1" max="10" value="3"></div>
        <div><label>Backgrounds</label>${select("src", ["auto", "ai", "stock"], "auto", 'id="src"')}
          <div class="small muted">Image engine: ${esc(st.image_generation)}</div></div>
        <div><label>Aspect ratio</label>${select("ar", ["auto", "4:5", "9:16", "1:1"], "auto", 'id="ar"')}</div>
        <div><label>Product to mention (optional)</label><select id="product"><option value="">None</option>${products.products
          .map((p) => `<option value="${p.id}">${esc(p.name)}</option>`).join("")}</select></div>
      </div>
      <label class="check" style="margin-top:14px"><input type="checkbox" id="auto"> Keep it going: save as an automation that makes and posts new ones on a schedule</label>
      <div class="form-grid" id="auto-opts" hidden>
        <div><label>TikTok account</label><select id="acct"><option value="">— none (drafts only) —</option>${accounts.accounts
          .map((a) => `<option value="${a.id}">@${esc(a.username)}</option>`).join("")}</select></div>
        <div><label>Post for this country's best times</label><select id="scountry"><option value="">— use the times below —</option></select></div>
        <div><label>Posts per day</label><input id="sppd" type="number" min="1" max="6" value="2"></div>
        <div><label>Posting times if no country (HH:MM, comma-separated)</label><input id="times" value="09:00, 18:00"></div>
        <div><label>Mode</label><select id="spub">
          <option value="browser:publish">Post from my browser (extension)</option>
          <option value="browser:draft">Save drafts from my browser (extension)</option>
          <option value="0">Send to TikTok drafts (official API)</option>
          <option value="1">Post by itself (official API)</option></select></div>
      </div>
      <div id="studio-consent"></div>
      <div class="row" style="margin-top:16px"><button class="primary" id="make">Make slideshows</button>
        <span class="muted small">About a minute per slideshow, three at a time.</span></div>
    </div>
    <div id="studio-out"></div>`;
  phoneModes($("#spub"));
  hideOnPhone($("#acct"));
  $("#auto").onchange = () => ($("#auto-opts").hidden = !$("#auto").checked);
  $("#spub").onchange = () => {
    $("#studio-consent").innerHTML = $("#spub").value === "1" ? consentBlock("consent-studio", { automatic: true }) : "";
    if ($("#spub").value === "1") wireConsent($("#consent-studio"), $("#make"));
  };
  api("/timing/countries").then((r) => {
    $("#scountry").insertAdjacentHTML("beforeend", r.countries.map((c) => `<option value="${c.code}">${esc(c.name)}</option>`).join(""));
  });
  $("#make").onclick = (e) =>
    busy(e.currentTarget, async () => {
      const brief = $("#brief").value.trim();
      if (!brief) throw new Error("Describe what you want first.");
      const ar = $("#ar").value;
      const { job_id } = await api("/studio", {
        method: "POST",
        body: {
          brief, count: +$("#count").value, image_source: $("#src").value, aspect_ratio: ar === "auto" ? null : ar,
          product_id: $("#product").value || null, save_as_automation: $("#auto").checked,
          tiktok_account_id: $("#acct").value || null, schedule: $("#auto").checked ? timesToCron($("#times").value, "*") : [],
          target_country: ($("#auto").checked && $("#scountry").value) || null, posts_per_day: +$("#sppd").value,
          auto_publish: $("#auto").checked && $("#spub").value === "1",
          via_browser: $("#auto").checked && $("#spub").value.startsWith("browser"),
          browser_action: $("#spub").value.split(":")[1] || "publish",
          ...($("#auto").checked && $("#spub").value === "1" ? (({ privacy_level, consent }) => ({ privacy_level, consent }))(readConsent($("#consent-studio"))) : {}),
        },
      });
      await pollJob(job_id, (job) => {
        const c = job.result?.campaign;
        $("#studio-out").innerHTML = `
          <h2>${esc(c?.name || "Planning…")}</h2>
          <p class="muted">${esc(job.message)}${c ? ` · ${esc(c.format_summary)}` : ""}</p>
          ${job.result?.automation_id ? `<p><a href="#automations">Saved as an automation →</a></p>` : ""}
          ${job.slideshows ? showGrid(job.slideshows) : ""}`;
      });
    });
};

async function pollJob(jobId, onUpdate) {
  for (;;) {
    const job = await api(`/jobs/${jobId}`);
    onUpdate(job);
    if (job.status === "failed") throw new Error(job.message || "Job failed");
    const pending = (job.slideshows || []).some((s) => !["completed", "failed"].includes(s.status));
    if (job.status === "completed" && !pending) return job;
    await sleep(3000);
  }
}

views.library = async () => {
  const params = new URLSearchParams(location.hash.split("?")[1] || "");
  const qs = new URLSearchParams({ q: params.get("q") || "", niche: params.get("niche") || "", sort: params.get("sort") || "followers", limit: "48" });
  const [data, niches, st] = await Promise.all([api(`/library?${qs}`), api("/library/niches"), api("/status")]);
  view.innerHTML = `
    <h1>Slideshow library</h1>
    <p class="sub">Real TikTok slideshow accounts and their best posts, scraped into your own database. Remix any post into your own slideshows.</p>
    <div class="form-grid">
      <div class="card"><h3>Add accounts or posts</h3>
        <textarea id="items" placeholder="@username&#10;https://www.tiktok.com/@user/photo/123…"></textarea>
        <div class="row" style="margin-top:8px"><button class="primary" id="import">Scrape</button><span class="small muted">Profiles list posts via a headless browser${st.apify ? " or Apify" : ""}.</span></div></div>
      <div class="card"><h3>Discover by keyword or #hashtag</h3>
        <input id="disc" placeholder="#studytok or 'morning routine slideshow'">
        <div class="row" style="margin-top:8px"><button class="primary" id="discover" ${st.apify ? "" : "disabled"}>Discover</button>
        ${st.apify ? "" : `<span class="small muted">Needs an Apify token in <a href="#settings">Settings</a>.</span>`}</div></div>
    </div>
    <div id="lib-job"></div>
    <div class="row" style="margin:16px 0 8px">
      <input id="q" class="grow" placeholder="Search accounts, bios, captions" value="${esc(params.get("q") || "")}">
      ${select("sort", ["followers", "likes", "recent"], params.get("sort") || "followers", 'id="sort" style="width:140px"')}
      <button id="search">Search</button>
    </div>
    <div class="row small">${[{ niche: "", profile_count: data.total }, ...niches.niches]
      .map((n) => `<a class="pill ${n.niche === (params.get("niche") || "") ? "live" : ""}" href="#library?niche=${encodeURIComponent(n.niche)}">${esc(n.niche || "all")} (${n.profile_count})</a>`).join("")}</div>
    <div class="grid" style="margin-top:14px;grid-template-columns:repeat(auto-fill,minmax(300px,1fr))">${data.profiles
      .map((p) => `<a class="card" href="#libprofile/${p.id}" style="color:inherit;text-decoration:none">
        <div class="row">${p.avatar ? `<img src="${esc(p.avatar)}" width="40" height="40" style="border-radius:50%">` : ""}
          <div class="grow"><b>@${esc(p.username)}</b> ${p.verified ? "✔" : ""}<div class="small muted">${fmt(p.followers)} followers · ${p.slideshow_count} slideshows</div></div>
          ${p.niche ? `<span class="pill">${esc(p.niche)}</span>` : ""}</div>
        <div class="row" style="margin-top:10px;gap:6px;flex-wrap:nowrap">${p.top_slideshows
          .map((x) => `<img src="${esc(x.cover)}" style="width:24%;aspect-ratio:3/4;object-fit:cover;border-radius:6px">`).join("")}</div>
      </a>`).join("") || `<p class="muted">Empty. Paste some TikTok slideshow accounts above.</p>`}</div>`;
  const go = () => {
    location.hash = `#library?q=${encodeURIComponent($("#q").value)}&sort=${$("#sort").value}&niche=${encodeURIComponent(params.get("niche") || "")}`;
  };
  $("#search").onclick = go;
  $("#q").onkeydown = (e) => e.key === "Enter" && go();
  const runJob = (btn, path, body) =>
    busy(btn, async () => {
      const { job_id } = await api(path, { method: "POST", body });
      await pollJob(job_id, (job) => ($("#lib-job").innerHTML = `<p class="muted"><span class="spinner"></span> ${esc(job.message)}</p>`));
      toast("Library updated");
      route();
    });
  $("#import").onclick = (e) => runJob(e.currentTarget, "/library/import", { items: $("#items").value.split(/\s+/).filter(Boolean) });
  $("#discover").onclick = (e) => runJob(e.currentTarget, "/library/discover", { query: $("#disc").value });
};

const fmt = (n) => (n >= 1e6 ? (n / 1e6).toFixed(1) + "M" : n >= 1e3 ? (n / 1e3).toFixed(1) + "K" : String(n ?? 0));

views.libprofile = async (id) => {
  const p = await api(`/library/profiles/${id}`);
  view.innerHTML = `
    <div class="row">${p.avatar ? `<img src="${esc(p.avatar)}" width="56" height="56" style="border-radius:50%">` : ""}
      <div class="grow"><h1>@${esc(p.username)}</h1><div class="muted">${esc(p.nickname)} · ${fmt(p.followers)} followers · ${fmt(p.total_likes)} likes
      ${p.niche ? ` · ${esc(p.niche)}` : ""}${p.product_medium ? ` · promotes: ${esc(p.product_medium)}` : ""}</div></div>
      <a class="btn" href="${esc(p.url)}" target="_blank">Open on TikTok</a><button class="danger" id="pdel">Remove</button></div>
    <p>${esc(p.bio)} ${p.link_in_bio ? `· <a href="https://${esc(p.link_in_bio.replace(/^https?:\/\//, ""))}" target="_blank">${esc(p.link_in_bio)}</a>` : ""}</p>
    <h2>Posts (${p.slideshows.length})</h2>
    <div class="grid" style="grid-template-columns:repeat(auto-fill,minmax(260px,1fr))">${p.slideshows
      .map((x) => `<div class="card" data-post="${x.id}">
        <div class="slides" style="gap:6px">${(x.images.length ? x.images : [x.cover]).filter(Boolean)
          .map((u) => `<img src="${esc(u)}" style="height:180px">`).join("")}</div>
        <div class="small">${esc(x.description).slice(0, 140)}</div>
        <div class="small muted">${fmt(x.views)} views · ${fmt(x.likes)} likes · ${fmt(x.saves)} saves ${x.is_slideshow ? "" : "· video"}</div>
        ${x.slide_texts?.length ? `<ol class="small">${x.slide_texts.map((t) => `<li>${esc(t)}</li>`).join("")}</ol>` : ""}
        <div class="row" style="margin-top:8px">
          ${x.is_slideshow ? `<button data-remix="${x.id}" class="primary">Remix</button><button data-read="${x.id}">Read slides</button>` : ""}
          <a class="btn" href="${esc(x.url)}" target="_blank">View</a></div>
      </div>`).join("")}</div>`;
  $("#pdel").onclick = async () => {
    if (!confirm("Remove this account from the library?")) return;
    await api(`/library/profiles/${id}`, { method: "DELETE" });
    location.hash = "#library";
  };
  view.onclick = (ev) => {
    const b = ev.target.closest("button");
    if (!b) return;
    if (b.dataset.read)
      busy(b, async () => {
        await api(`/library/posts/${b.dataset.read}/analyze`, { method: "POST" });
        route();
      });
    if (b.dataset.remix)
      busy(b, async () => {
        const topic = prompt("New topic for your version (leave empty to keep the same subject):", "") ?? null;
        if (topic === null) return;
        const { slideshow_ids } = await api(`/library/posts/${b.dataset.remix}/remix`, {
          method: "POST", body: { count: 1, topic: topic || null, image_source: "auto" },
        });
        location.hash = `#slideshow/${slideshow_ids[0]}`;
      });
  };
};

views.settings = async () => {
  const [data, ext] = await Promise.all([api("/settings"), api("/extension/status")]);
  const seen = ext.extension?.at ? new Date(ext.extension.at) : null;
  const online = seen && Date.now() - seen.getTime() < 3 * 60 * 1000;
  const v = data.values;
  const field = (key, label, type = "text", ph = "") => `<div><label>${label}${v[key].from_env ? ` <span class="pill">from ${esc(v[key].from_env)}</span>` : ""}</label>
    <input name="${key}" type="${type}" placeholder="${esc(v[key].set && type === "password" ? v[key].value : ph)}" ${type === "password" ? "" : `value="${esc(v[key].value)}"`}></div>`;
  const provOpts = (cur) => `<option value="">auto-detect from key</option>` +
    Object.entries(data.llm_providers).map(([k, p]) => `<option value="${k}" ${k === cur ? "selected" : ""}>${esc(p.label)}</option>`).join("");
  view.innerHTML = `
    <h1>Settings</h1>
    <p class="sub">Paste any AI key — the provider is detected from it. ${PHONE ? "Keys are encrypted on this phone." : "Keys are stored in this server's database."}</p>
    ${PHONE ? phoneCard(ext, field) : ""}
    <div class="card" id="browser-poster" ${PHONE ? "hidden" : ""}>
      <h3>Browser poster (no TikTok developer app needed)</h3>
      <p class="small">
        <span class="pill ${online ? "ok" : "bad"}">${online ? "extension online" : "extension offline"}</span>
        ${seen ? `last check-in ${esc(seen.toLocaleString())}` : "never checked in"}
        ${ext.extension?.username ? ` · posting as @${esc(ext.extension.username)}` : ""}
        · ${ext.queued} queued · ${ext.done_last_24h}/${ext.posts_per_day} in the last 24h
        ${ext.waiting_for_draft_support ? ` · <b>${ext.waiting_for_draft_support} draft(s) waiting for draft support</b>` : ""}
      </p>
      <div class="form-grid">
        ${field("browser_posts_per_day", "Max posts per 24h", "text", "6")}
        ${field("browser_min_gap_minutes", "Minimum minutes between posts", "text", "20")}
        ${field("browser_username", "Your TikTok @username (for view counts, if the extension can't detect it)", "text", "")}
        <div><label>&nbsp;</label><button type="button" id="ext-download">Download extension</button></div>
      </div>
      <p class="small muted">Install: unzip → chrome://extensions → Developer mode → Load unpacked. In the extension popup enter
        this address (${esc(location.origin)}) and your Slidemill password, then keep a logged-in tiktok.com tab open.
        Posting this way uses TikTok's private web endpoints and is against TikTok's terms; keep the limits low.</p>
    </div>
    <div class="card" style="margin-top:14px">
      <h3>AI for writing (and reading slides)</h3>
      <div class="form-grid">
        <div><label>Provider</label><select name="llm_provider">${provOpts(v.llm_provider.value)}</select></div>
        ${field("llm_api_key", "API key", "password", "sk-ant-… / sk-… / AIza… / sk-or-… / gsk_…")}
        ${field("llm_model", "Model (blank = provider default)", "text", "")}
        ${field("llm_base_url", "Base URL (custom / Ollama / proxies)", "text", "")}
      </div>
      <p class="small muted">Defaults: ${Object.entries(data.llm_providers).filter(([, p]) => p.default_model).map(([k, p]) => `${esc(k)} → ${esc(p.default_model)}`).join(" · ")}</p>
    </div>
    <div class="card" style="margin-top:14px">
      <h3>AI images</h3>
      <div class="form-grid">
        <div><label>Image provider</label>${select("image_provider", ["auto", ...Object.keys(data.image_providers)], v.image_provider.value || "auto")}</div>
        ${field("image_api_key", "Image API key (blank = reuse the AI key when it can make images)", "password")}
        ${field("image_model", "Image model (blank = default)")}
      </div>
      <p class="small muted">auto: OpenAI or Gemini keys make images too; otherwise Pollinations is used for free (watermarked).</p>
    </div>
    <div class="card" style="margin-top:14px">
      <h3>Stock photos & scraping</h3>
      <div class="form-grid">
        ${field("pexels_api_key", "Pexels API key", "password")}
        ${field("unsplash_access_key", "Unsplash access key", "password")}
        ${field("apify_token", "Apify token (library discovery)", "password")}
      </div>
    </div>
    <div class="row" style="margin-top:16px"><button class="primary" id="save">Save</button><button id="test">Test AI + images</button></div>
    <div id="test-out"></div>`;
  if (PHONE) wirePhoneCard();
  $("#ext-download").onclick = (e) =>
    busy(e.currentTarget, async () => {
      const res = await fetch("/api/v1/extension/download", { headers: storedKey() ? { Authorization: `Bearer ${storedKey()}` } : {} });
      if (!res.ok) throw new Error(`Download failed (${res.status})`);
      const a = Object.assign(document.createElement("a"), { href: URL.createObjectURL(await res.blob()), download: "slidemill-tiktok-poster.zip" });
      a.click();
      URL.revokeObjectURL(a.href);
    });
  $("#save").onclick = (e) =>
    busy(e.currentTarget, async () => {
      const values = {};
      $$("input, select", view).forEach((el) => {
        if (!el.name || el.closest("[hidden]")) return;
        if (el.type === "password" && !el.value) return; // keep stored secret
        values[el.name] = el.name === "image_provider" && el.value === "auto" ? "" : el.value;
      });
      await api("/settings", { method: "PUT", body: { values } });
      toast("Saved");
      statusPills();
      route();
    });
  $("#test").onclick = (e) =>
    busy(e.currentTarget, async () => {
      const r = await api("/settings/test", { method: "POST" });
      $("#test-out").innerHTML = `<div class="card" style="margin-top:14px">
        <div>${r.ai.ok ? "✅" : "❌"} AI: ${esc(r.ai.detail)}</div><div>${r.images.ok ? "✅" : "❌"} Images: ${esc(r.images.detail)}</div></div>`;
    });
};

function phoneCard(ext, field) {
  return `<div class="card" id="phone-poster">
      <h3>Posting from this phone</h3>
      <p class="small" id="poster-line"><span class="spinner"></span></p>
      <p class="small">${ext.queued} queued · ${ext.done_last_24h}/${ext.posts_per_day} posted in the last 24h
        ${ext.waiting_to_share ? ` · ${ext.waiting_to_share} waiting to hand to the TikTok app` : ""}
        ${ext.next_due_at ? ` · next at ${esc(new Date(ext.next_due_at).toLocaleString())}` : ""}</p>
      <div class="form-grid">
        ${field("browser_posts_per_day", "Max posts per 24h", "text", "6")}
        ${field("browser_min_gap_minutes", "Minimum minutes between posts", "text", "20")}
        ${field("browser_username", "Your TikTok @username (for view counts, if not detected)", "text", "")}
      </div>
      <div class="row" style="margin-top:12px">
        <button type="button" id="open-tiktok">Open the TikTok tab</button>
        <button type="button" id="backup">Back up everything</button>
        <label class="btn" style="margin:0">Restore a backup<input type="file" id="restore" accept=".zip,application/zip" hidden></label>
      </div>
      <p class="small muted">Auto-posting uses TikTok's private web endpoints from the TikTok tab, which is against TikTok's terms;
        keep the limits low. "Send to the TikTok app" posts are finished by you in TikTok and carry no such risk.
        A backup holds your slideshows and settings; API keys are tied to this phone, so re-enter them after restoring elsewhere.</p>
    </div>`;
}

function wirePhoneCard() {
  api("/status").then((st) => {
    const p = st.poster || {};
    $("#poster-line").innerHTML = `<span class="pill ${p.logged_in ? "ok" : "bad"}">${p.logged_in ? `TikTok: @${esc(p.username)}` : "not logged in to TikTok"}</span>
      <span class="pill ${p.auto_post ? "ok" : "bad"}">${p.auto_post ? "auto-posting on" : "auto-posting off (Phone tab)"}</span> ${esc(p.status || "")}`;
  }).catch(() => {});
  $("#open-tiktok").onclick = () => APP?.openTikTok();
  $("#backup").onclick = () => (APP ? APP.saveBackup() : toast("Only in the app", true));
  $("#restore").onchange = async (e) => {
    const file = e.target.files[0];
    if (!file || !confirm("Replace everything on this phone with this backup?")) return;
    const form = new FormData();
    form.append("file", file);
    try { await api("/backup/restore", { method: "POST", form }); toast("Restored"); route(); } catch (err) { toast(err.message, true); }
  };
}

// ---- virality prediction -------------------------------------------------------------------

const DIM_LABELS = { visual_pull: "Visual pull", curiosity: "Curiosity", clarity: "Clarity", relatability: "Relatability", shareability: "Shareability" };
const scoreClass = (v) => (v >= 70 ? "ok" : v >= 55 ? "live" : "bad");

function predictionHtml(p) {
  if (!p) {
    return `<div class="row"><div class="grow"><h3>Virality prediction</h3>
      <p class="small muted">Before you post: hook strength, how many viewers swipe to the end, and what to fix. Uses your AI key (one call).</p></div>
      <button class="primary" id="predict">Predict viral potential</button></div>`;
  }
  const bars = (p.slides || []).map((sl, i) => `<div class="att" title="Slide ${i + 1}: ${sl.attention}% still watching${sl.note ? " · " + esc(sl.note) : ""}">
      <div class="att-fill ${p.biggest_drop === i + 1 ? "drop" : ""}" style="height:${Math.max(4, sl.attention)}%"></div><span>${i + 1}</span></div>`).join("");
  return `<div class="row" style="align-items:flex-start">
      <div class="gauge ${scoreClass(p.viral_potential)}"><b>${p.viral_potential}</b><span>/100</span></div>
      <div class="grow"><h3 style="margin:0">${esc(p.verdict)} <span class="pill ${scoreClass(p.viral_potential)}">${esc(p.verdict_tag)}</span></h3>
        <div class="row small" style="margin-top:6px;gap:18px">
          <span>Hook score <b>${p.hook_score}</b></span><span>Hold rate <b>${p.hold_rate}%</b> reach the last slide</span>
          ${p.biggest_drop ? `<span>Biggest drop after slide <b>${p.biggest_drop}</b></span>` : ""}</div></div>
      <button id="predict">Predict again</button></div>
    <div class="pred-grid">
      <div><div class="small muted">Attention by slide</div><div class="att-row">${bars}</div></div>
      <div>${Object.entries(p.dimensions || {}).map(([k, v]) => `<div class="dim"><span>${esc(DIM_LABELS[k] || k)}</span>
        <div class="dim-bar"><div style="width:${v}%"></div></div><b>${v}</b></div>`).join("")}</div>
    </div>
    ${p.tips?.length ? `<div class="small" style="margin-top:10px"><b>Fix first</b><ul style="margin:4px 0 0 18px;padding:0">${p.tips.map((t) => `<li>${esc(t)}</li>`).join("")}</ul></div>` : ""}
    ${p.better_hook ? `<div class="row small" style="margin-top:10px"><span class="grow">Stronger hook: <b>“${esc(p.better_hook)}”</b></span>
      <button class="small" id="use-hook">Use this hook</button></div>` : ""}
    <p class="small muted" style="margin:10px 0 0">${esc(p.basis)}. A prediction, not a promise: check Analytics to see if it tracks your real views.</p>`;
}

function wirePrediction(id, spec) {
  const card = $("#predict-card");
  if (!card) return;
  card.onclick = async (ev) => {
    const btn = ev.target.closest("button");
    if (!btn) return;
    if (btn.id === "predict") {
      busy(btn, async () => {
        const p = await api(`/slideshows/${id}/predict`, { method: "POST" });
        card.innerHTML = predictionHtml(p);
      });
    } else if (btn.id === "use-hook") {
      busy(btn, async () => {
        const s = await api(`/slideshows/${id}`);
        const hook = s.prediction.better_hook;
        const next = structuredClone(spec);
        const first = next.slides[0];
        if (first.text_items.length) first.text_items[0].text = hook;
        else first.text_items.push({ text: hook, font: "tiktok", font_size: "large", text_style: "outline", text_color: "white" });
        await api(`/slideshows/${id}`, { method: "PUT", body: { spec: next } });
        toast("Hook replaced and re-rendered. Predict again to compare.");
        route();
      });
    }
  };
}

// ---- TikTok direct-post consent (content-sharing guidelines) ---------------------------

const PRIVACY_LABELS = {
  PUBLIC_TO_EVERYONE: "Everyone", MUTUAL_FOLLOW_FRIENDS: "Friends", FOLLOWER_OF_CREATOR: "Followers", SELF_ONLY: "Only me",
};
const MUSIC_URL = "https://www.tiktok.com/legal/page/global/music-usage-confirmation/en";
const BC_URL = "https://www.tiktok.com/legal/page/global/bc-policy/en";

// Privacy has no default and every toggle starts off: TikTok requires an explicit choice.
function consentBlock(id, { creator = null, saved = null, automatic = false } = {}) {
  const options = creator?.privacy_level_options?.length ? creator.privacy_level_options : Object.keys(PRIVACY_LABELS);
  const pick = saved?.consented ? saved.visibility : "";
  return `<div class="card" style="margin-top:10px;background:var(--bg)" id="${id}">
    ${creator ? `<div class="row">${creator.avatar_url ? `<img src="${esc(creator.avatar_url)}" width="28" height="28" style="border-radius:50%">` : ""}
      <div>Posting to <b>${esc(creator.nickname)}</b> <span class="muted">@${esc(creator.username)}</span></div></div>` : ""}
    <label>Who can see ${automatic ? "these posts" : "this post"}</label>
    <select data-c="privacy"><option value="" ${pick ? "" : "selected"} disabled>Choose…</option>${options
      .map((o) => `<option value="${o}" ${o === pick ? "selected" : ""}>${esc(PRIVACY_LABELS[o] || o)}</option>`).join("")}</select>
    <label class="check"><input type="checkbox" data-c="comments" ${saved?.consented && saved.allow_comments ? "checked" : ""}
      ${creator?.comment_disabled ? "disabled" : ""}> Allow comments${creator?.comment_disabled ? " (turned off in your TikTok settings)" : ""}</label>
    <label class="check"><input type="checkbox" data-c="disclose" ${saved?.brand_organic || saved?.branded_content ? "checked" : ""}> Disclose commercial content</label>
    <div data-c="disclose-opts" ${saved?.brand_organic || saved?.branded_content ? "" : "hidden"} style="padding-left:24px">
      <label class="check"><input type="checkbox" data-c="own" ${saved?.brand_organic ? "checked" : ""}> Your brand — promoting yourself or your own business</label>
      <label class="check"><input type="checkbox" data-c="branded" ${saved?.branded_content ? "checked" : ""}> Branded content — paid partnership or a third party's brand</label>
    </div>
    <p class="small" data-c="label-notice" hidden></p>
    <label class="check" style="margin-top:10px"><input type="checkbox" data-c="consent" ${saved?.consented ? "checked" : ""}>
      <span data-c="consent-text"></span></label>
    ${automatic ? `<p class="small muted">Applies to every automatic post until you change it here.</p>` : ""}
  </div>`;
}

function wireConsent(root, button = null) {
  const q = (k) => root.querySelector(`[data-c="${k}"]`);
  const update = () => {
    q("disclose-opts").hidden = !q("disclose").checked;
    const branded = q("disclose").checked && q("branded").checked;
    const own = q("disclose").checked && q("own").checked;
    const incomplete = q("disclose").checked && !branded && !own;
    q("label-notice").hidden = !(branded || own);
    q("label-notice").textContent = branded
      ? 'Your post will be labeled "Paid partnership".'
      : 'Your post will be labeled "Promotional content".';
    if (button) {
      button.disabled = incomplete;
      button.title = incomplete ? "Choose Your brand and/or Branded content, or turn disclosure off." : "";
    }
    q("consent-text").innerHTML = `By posting, you agree to TikTok's <a href="${MUSIC_URL}" target="_blank">Music Usage Confirmation</a>${
      branded ? ` and <a href="${BC_URL}" target="_blank">Branded Content Policy</a>` : ""}.`;
    const onlyMe = [...q("privacy").options].find((o) => o.value === "SELF_ONLY");
    if (onlyMe) {
      onlyMe.disabled = branded; // branded content can't be private
      onlyMe.title = branded ? "Branded content can't be private" : "";
    }
    if (branded && q("privacy").value === "SELF_ONLY") q("privacy").value = "";
  };
  root.addEventListener("change", update);
  update();
}

function readConsent(root) {
  const q = (k) => root.querySelector(`[data-c="${k}"]`);
  const disclose = q("disclose").checked;
  const out = {
    privacy_level: q("privacy").value || null,
    allow_comments: q("comments").checked,
    brand_organic: disclose && q("own").checked,
    branded_content: disclose && q("branded").checked,
    consent: q("consent").checked,
  };
  if (!out.privacy_level) throw new Error("Choose who can see the post.");
  if (disclose && !out.brand_organic && !out.branded_content) throw new Error("Pick what kind of commercial content it is, or turn disclosure off.");
  if (!out.consent) throw new Error("Tick the TikTok agreement to post.");
  return out;
}

// ---- best times to post ---------------------------------------------------------------

const DAY_LABELS = { mon: "Mon", tue: "Tue", wed: "Wed", thu: "Thu", fri: "Fri", sat: "Sat", sun: "Sun" };
// Sequential blue ramp, dark surface: low = deep (recedes), high = light.
const RAMP = ["#0d366b", "#104281", "#184f95", "#1c5cab", "#256abf", "#2a78d6", "#3987e5", "#5598e7", "#6da7ec", "#86b6ef", "#9ec5f4", "#b7d3f6", "#cde2fb"];
const heatColor = (v) => RAMP[Math.min(RAMP.length - 1, Math.max(0, Math.round(v * (RAMP.length - 1))))];

function heatmap(plan) {
  const picks = new Set();
  Object.entries(plan.schedule).forEach(([d, times]) => times.forEach((t) => picks.add(`${d}-${parseInt(t, 10)}`)));
  const days = Object.keys(DAY_LABELS);
  let html = `<div class="heat" role="img" aria-label="Audience activity by day and hour in ${esc(plan.timezone)}"><div></div>`;
  for (let h = 0; h < 24; h++) html += `<div class="hr">${h % 3 === 0 ? h : ""}</div>`;
  days.forEach((d, di) => {
    html += `<div class="lab">${DAY_LABELS[d]}${plan.weekend.includes(d) ? "*" : ""}</div>`;
    plan.heatmap[di].forEach((v, h) => {
      const pick = picks.has(`${d}-${h}`);
      html += `<div class="cell ${pick ? "pick" : ""}" style="background:${heatColor(v)}" data-tip="${DAY_LABELS[d]} ${String(h).padStart(2, "0")}:00 · reach score ${Math.round(v * 100)}${pick ? " · scheduled" : ""}"></div>`;
    });
  });
  html += `</div><div class="heat-legend">low <span class="ramp"></span> high · ringed = scheduled post · * = weekend</div>`;
  return html;
}

function attachTips(root) {
  let tip;
  root.addEventListener("mousemove", (e) => {
    const cell = e.target.closest("[data-tip]");
    if (!cell) { tip?.remove(); tip = null; return; }
    tip ??= document.body.appendChild(Object.assign(document.createElement("div"), { className: "heat-tip" }));
    tip.textContent = cell.dataset.tip;
    tip.style.left = `${e.clientX + 12}px`;
    tip.style.top = `${e.clientY + 12}px`;
  });
  root.addEventListener("mouseleave", () => { tip?.remove(); tip = null; });
}

views.timing = async () => {
  const [countries, autos, accounts] = await Promise.all([api("/timing/countries"), api("/automations"), api("/tiktok/accounts")]);
  const saved = (() => { try { return localStorage.getItem("slidemill_country") || "US"; } catch { return "US"; } })();
  view.innerHTML = `
    <h1>Best times to post</h1>
    <p class="sub">Pick the country you want to reach. Slidemill builds a weekly schedule in that country's local time and can post on its own at those hours.</p>
    <div class="card"><div class="form-grid">
      <div><label>Target country</label><select id="country">${countries.countries
        .map((c) => `<option value="${c.code}" ${c.code === saved ? "selected" : ""}>${esc(c.name)}${c.multi_zone ? " (all time zones)" : ""}</option>`).join("")}</select></div>
      <div><label>Posts per day</label><input id="ppd" type="number" min="1" max="6" value="3"></div>
      <div><label>Learn from this account's results (optional)</label><select id="tacct"><option value="">—</option>${accounts.accounts
        .map((a) => `<option value="${a.id}">@${esc(a.username)}</option>`).join("")}</select></div>
    </div></div>
    <div id="plan"></div>
    <h2>Auto-post at these times</h2>
    <div class="card">
      ${autos.automations.length ? `<div class="form-grid">
        <div><label>Automation</label><select id="auto-sel">${autos.automations.map((a) => `<option value="${a.id}">${esc(a.title)}${a.target_country ? ` (now: ${esc(a.target_country)})` : ""}</option>`).join("")}</select></div>
        <div><label>Mode</label><select id="pub">
          <option value="browser:publish">Post from my browser (extension)</option>
          <option value="browser:draft">Save drafts from my browser (extension)</option>
          <option value="0">Send to my TikTok drafts (official API)</option>
          <option value="1">Post by itself (official API)</option></select></div>
      </div>
      <div id="pub-consent"></div>
      <div class="row" style="margin-top:14px"><button class="primary" id="apply">Turn on auto-posting</button>
      <span class="small muted">Each slot: the slideshow is made 15 minutes early, then posted on time (± a few minutes so it doesn't look robotic).</span></div>
      <div id="apply-out"></div>`
      : `<p class="muted">Create an automation first (or use <a href="#studio">Studio</a> and tick "keep it going").</p>`}
    </div>`;
  attachTips(view);
  phoneModes($("#pub"));
  hideOnPhone($("#tacct"));
  const load = async () => {
    try { localStorage.setItem("slidemill_country", $("#country").value); } catch { /* ignore */ }
    const qs = new URLSearchParams({ country: $("#country").value, posts_per_day: $("#ppd").value });
    if ($("#tacct").value) qs.set("tiktok_account_id", $("#tacct").value);
    const plan = await api(`/timing/best?${qs}`);
    const days = Object.keys(DAY_LABELS);
    $("#plan").innerHTML = `
      <h2>${esc(plan.name)} · times in ${esc(plan.timezone)}</h2>
      <div class="card">${heatmap(plan)}</div>
      <div class="card" style="margin-top:12px;overflow-x:auto"><table>
        <tr>${days.map((d) => `<th>${DAY_LABELS[d]}</th>`).join("")}</tr>
        <tr>${days.map((d) => `<td>${plan.schedule[d].map(esc).join("<br>")}</td>`).join("")}</tr></table>
        <p class="small muted">Based on: ${plan.sources.map(esc).join(", ")}. Confidence: ${esc(plan.confidence)}.
        ${plan.zones.length > 1 ? `Weighted across ${plan.zones.map((z) => `${esc(z.zone.split("/").pop().replace("_", " "))} ${Math.round(z.share * 100)}%`).join(", ")}.` : ""}
        Scrape accounts from this country into the library, or post for a few weeks, and these times adapt to real results.</p></div>`;
  };
  ["country", "ppd", "tacct"].forEach((id) => ($(`#${id}`).onchange = () => load().catch((e) => toast(e.message, true))));
  await load();
  const pubSel = $("#pub");
  if (pubSel) {
    const showConsent = () => {
      const auto = autos.automations.find((x) => x.id === $("#auto-sel").value);
      $("#pub-consent").innerHTML = pubSel.value === "1" ? consentBlock("consent-timing", { saved: auto?.post_settings, automatic: true }) : "";
      if (pubSel.value === "1") wireConsent($("#consent-timing"), $("#apply"));
    };
    pubSel.onchange = showConsent;
    $("#auto-sel").onchange = showConsent;
  }
  const apply = $("#apply");
  if (apply)
    apply.onclick = (e) =>
      busy(e.currentTarget, async () => {
        const r = await api(`/automations/${$("#auto-sel").value}/apply-timing`, {
          method: "POST",
          body: {
            country: $("#country").value, posts_per_day: +$("#ppd").value, auto_publish: $("#pub").value === "1",
            via_browser: $("#pub").value.startsWith("browser"), browser_action: $("#pub").value.split(":")[1] || "publish",
            ...($("#pub").value === "1" ? readConsent($("#consent-timing")) : {}),
          },
        });
        const next = r.automation.next_runs.slice(0, 3).map((t) => new Date(t).toLocaleString()).join(" · ");
        $("#apply-out").innerHTML = `<p>✅ <b>${esc(r.automation.title)}</b> now posts ${r.plan.posts_per_day}×/day for ${esc(r.plan.name)}. Next: ${esc(next || "—")} (your local time)</p>
          ${r.warnings.map((w) => `<p class="small" style="color:var(--warn)">⚠ ${esc(w)}</p>`).join("")}`;
      });
};

// ---- router ----------------------------------------------------------------------------

async function route() {
  view.onclick = null;
  const [name, arg] = (location.hash.slice(1).split("?")[0] || "home").split("/");
  $$("nav a").forEach((a) => a.classList.toggle("active", a.dataset.view === name));
  const fn = views[name] || views.home;
  try { await fn(arg); }
  catch (e) { view.innerHTML = `<h1>Something went wrong</h1><p class="muted">${esc(e.message)}</p>`; }
}

async function statusPills() {
  try {
    const st = await api("/status");
    $("#status-pills").innerHTML = [
      [st.ai ? `AI: ${st.ai_provider}` : "AI", st.ai], ["Images", !st.image_generation.startsWith("unavailable")],
      ["TikTok", PHONE ? st.poster?.logged_in : st.tiktok], ["Apify", st.apify],
    ].map(([k, ok]) => `<span class="pill ${ok ? "ok" : "bad"}">${k}</span>`).join("");
  } catch { /* shown by the view */ }
}

window.addEventListener("hashchange", route);
detectPlatform().then(() => { route(); statusPills(); });
