const DEFAULTS = { server: "http://localhost:8000", key: "", enabled: true, openTab: false };
const $ = (id) => document.getElementById(id);

function show(text) { $("status").textContent = text; }

async function load() {
  const c = { ...DEFAULTS, ...(await chrome.storage.local.get(Object.keys(DEFAULTS))) };
  $("server").value = c.server;
  $("key").value = c.key;
  $("enabled").checked = c.enabled;
  $("openTab").checked = c.openTab;
  const { status } = await chrome.storage.session.get("status");
  show(status ? `${status.text}\n${new Date(status.at).toLocaleTimeString()}` : "Not checked yet.");
}

// Slidemill may run on another machine: ask for access to that address only.
async function ensureAccess(server) {
  let origin;
  try { origin = new URL(server).origin + "/*"; } catch (_) { throw new Error("That address isn't a valid URL."); }
  if (await chrome.permissions.contains({ origins: [origin] })) return;
  if (!(await chrome.permissions.request({ origins: [origin] }))) throw new Error("Access to " + origin + " was not granted.");
}

$("save").onclick = async () => {
  try {
    const server = $("server").value.trim().replace(/\/+$/, "") || DEFAULTS.server;
    await ensureAccess(server);
    await chrome.storage.local.set({ server, key: $("key").value.trim(), enabled: $("enabled").checked, openTab: $("openTab").checked });
    show("Saved. Checking…");
    await chrome.runtime.sendMessage({ type: "slidemillPollNow" });
    await load();
  } catch (e) { show("✗ " + e.message); }
};

$("test").onclick = async () => {
  show("Checking…");
  await chrome.runtime.sendMessage({ type: "slidemillPollNow" });
  await load();
};

load();
