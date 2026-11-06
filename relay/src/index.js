// Slidemill relay: gives the phone app a fixed https address for Claude's connector.
//
// The phone dials out to /phone with its secret and holds a WebSocket open (it has no public address
// of its own). Claude POSTs MCP messages to /mcp/<token>; the relay hands each one to the phone and
// returns the phone's answer. <token> is derived from the phone's secret, so Claude's URL can't be used
// to impersonate the phone. Runs on Cloudflare's free plan: one Durable Object per phone, with
// WebSocket hibernation so an idle connection costs nothing.

import { DurableObject } from "cloudflare:workers";
import { claudeToken } from "./token.js";

const VERSIONS = ["2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"];
const TIMEOUT_MS = 290_000; // rendering with AI backgrounds can take a few minutes
const MAX_BODY = 1_000_000;
const ORIGINS = ["https://claude.ai", "https://claude.com"];
// Tools that change things: while the phone is offline these wait here and run when it's back.
const QUEUEABLE = ["create_slideshow", "import_slides", "save_draft", "delete_slideshow"];
const MAX_QUEUE = 20;
// A sleeping phone: its connection dies silently while the screen is off. Before a call, the relay checks
// the link with a ping (phones that say hello v2 answer pong) and, when the phone isn't there, wakes it
// with a Firebase push and waits for it to reconnect.
const PROBE_MS = 4_000;
const WAKE_WAIT_MS = 20_000;

const json = (status, body, headers = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });

const rpcError = (id, code, message) => ({ jsonrpc: "2.0", id: id ?? null, error: { code, message } });

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/") return new Response("Slidemill relay is running. Connect it in the Slidemill app: Settings, Connect Claude.\n");

    if (url.pathname === "/phone") {
      if (request.headers.get("upgrade") !== "websocket") return new Response("Expected a WebSocket", { status: 426 });
      const secret = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
      if (!/^[0-9a-f]{64}$/.test(secret)) return new Response("Missing or malformed phone secret", { status: 401 });
      const room = env.RELAY.get(env.RELAY.idFromName(await claudeToken(secret)));
      return room.fetch(request);
    }

    const m = url.pathname.match(/^\/mcp\/([0-9a-f]{32})\/?$/);
    if (m) {
      const origin = request.headers.get("origin");
      if (origin && !ORIGINS.includes(origin)) return new Response("Forbidden origin", { status: 403 });
      return env.RELAY.get(env.RELAY.idFromName(m[1])).fetch(request);
    }
    return new Response("Not found", { status: 404 });
  },
};

export class Relay extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.pending = new Map(); // request id -> { parts, done }
    this.probes = new Set(); // waiting for the phone's pong
  }

  async fetch(request) {
    if (new URL(request.url).pathname === "/phone") return this.connectPhone();
    if (request.method !== "POST") return json(405, rpcError(null, -32000, "Method not allowed: this server only answers POST."), { allow: "POST" });

    const body = await request.text();
    if (body.length > MAX_BODY) return json(413, rpcError(null, -32600, "Request too large"));
    let msg = null;
    try { msg = JSON.parse(body); } catch { return json(400, rpcError(null, -32700, "Parse error")); }

    // The newest open connection (a replaced one can still be closing); woken by push if it's asleep.
    const phone = await this.livePhone();
    if (!phone) return this.offline(msg, body);
    const reply = await this.ask(phone, body);
    // Connected but no answer (dropped mid-call): don't queue it, the phone may have done it already.
    if (!reply) return this.offline(msg, null);

    // Remember what the phone says about itself, so Claude can still connect while it's offline.
    if (reply.status === 200 && msg && (msg.method === "initialize" || msg.method === "tools/list")) {
      try {
        const out = JSON.parse(reply.data);
        if (out.result) await this.ctx.storage.put(msg.method, out.result);
      } catch { /* not cacheable */ }
    }
    return new Response(reply.status === 202 ? null : reply.data, { status: reply.status, headers: { "content-type": reply.type || "application/json" } });
  }

  openPhone() {
    return this.ctx.getWebSockets().filter((ws) => ws.readyState === WebSocket.OPEN).at(-1);
  }

  /** The phone's connection if it's really there; otherwise wake it by push and wait for it. */
  async livePhone() {
    let phone = this.openPhone();
    if (phone && (phone.deserializeAttachment() || {}).v >= 2 && !(await this.probe(phone))) {
      try { phone.close(4002, "no answer"); } catch { /* already gone */ }
      phone = null;
    }
    if (phone) return phone;
    if (!(await this.wake())) return null;
    for (let waited = 0; waited < WAKE_WAIT_MS; waited += 250) {
      await new Promise((r) => setTimeout(r, 250));
      const back = this.openPhone();
      if (back) return back;
    }
    return null;
  }

  /** Ping the phone; true if it answers within PROBE_MS. */
  probe(phone) {
    return new Promise((resolve) => {
      const done = (ok) => { clearTimeout(timer); this.probes.delete(done); resolve(ok); };
      const timer = setTimeout(() => done(false), PROBE_MS);
      this.probes.add(done);
      try { phone.send(JSON.stringify({ t: "ping" })); } catch { done(false); }
    });
  }

  /** Send the phone a wake-up push (Firebase Cloud Messaging). False when push isn't set up. */
  async wake() {
    const token = await this.ctx.storage.get("push_token");
    if (!token) return false;
    if (this.env.FCM_FAKE) return true; // tests: the test plays the woken phone
    if (!this.env.FCM_SERVICE_ACCOUNT) return false;
    try {
      const sa = JSON.parse(this.env.FCM_SERVICE_ACCOUNT);
      const r = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${await fcmAccessToken(sa)}`, "content-type": "application/json" },
        body: JSON.stringify({ message: { token, data: { t: "wake" }, android: { priority: "HIGH", ttl: "30s" } } }),
      });
      if (r.status === 404) await this.ctx.storage.delete("push_token"); // app uninstalled or token replaced
      return r.ok;
    } catch {
      return false;
    }
  }

  connectPhone() {
    // One phone per link: a new connection replaces the old one.
    for (const old of this.ctx.getWebSockets()) {
      try { old.close(4001, "replaced"); } catch { /* already gone */ }
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    this.flushQueue(server); // what Claude asked for while the phone was away
    return new Response(null, { status: 101, webSocket: client });
  }

  ask(phone, body) {
    const id = crypto.randomUUID();
    return new Promise((resolve) => {
      const timer = setTimeout(() => finish(null), TIMEOUT_MS);
      const finish = (reply) => {
        clearTimeout(timer);
        this.pending.delete(id);
        resolve(reply);
      };
      this.pending.set(id, { parts: [], done: finish });
      try {
        phone.send(JSON.stringify({ t: "req", id, method: "POST", body }));
      } catch {
        finish(null);
      }
    });
  }

  async webSocketMessage(ws, data) {
    if (typeof data !== "string") return;
    let m;
    try { m = JSON.parse(data); } catch { return; }
    if (m.t === "ack") return this.forget(m.id);
    if (m.t === "ping") return ws.send(JSON.stringify({ t: "pong" })); // the phone checking the link after a sleep
    if (m.t === "pong") return [...this.probes].forEach((done) => done(true));
    if (m.t === "hello") {
      // v2 phones answer pings; push is the token the relay wakes it with.
      ws.serializeAttachment({ v: Number(m.v) || 1 });
      if (typeof m.push === "string") await (m.push ? this.ctx.storage.put("push_token", m.push) : this.ctx.storage.delete("push_token"));
      return;
    }
    const p = this.pending.get(m.id);
    if (!p) return;
    if (m.t === "part") p.parts.push(m.data);
    else if (m.t === "res") p.done({ status: m.status, type: m.type, data: p.parts.join("") + (m.data ?? "") });
  }

  async webSocketClose(ws, code) {
    for (const p of [...this.pending.values()]) p.done(null);
    try { ws.close(code === 1005 ? 1000 : code, "closed"); } catch { /* already closed */ }
  }

  async webSocketError() {
    for (const p of [...this.pending.values()]) p.done(null);
  }

  // The phone isn't connected (or didn't answer): answer what we can so Claude can tell the user.
  async flushQueue(ws) {
    for (const item of (await this.ctx.storage.get("queue")) || []) {
      try { ws.send(JSON.stringify({ t: "queued", id: item.id, body: item.body })); } catch { return; }
    }
  }

  // The phone has it (and remembers it, so a resend can't run it twice).
  async forget(id) {
    const queue = (await this.ctx.storage.get("queue")) || [];
    await this.ctx.storage.put("queue", queue.filter((q) => q.id !== id));
  }

  async offline(msg, body) {
    if (!msg || Array.isArray(msg) || msg.id === undefined || !msg.method) return new Response(null, { status: 202 });
    const queue = (await this.ctx.storage.get("queue")) || [];
    const waiting = queue.length ? ` ${queue.length} earlier request(s) are waiting for it.` : "";
    const say = `Slidemill's phone is offline. Open the Slidemill app on the phone (and keep it running), then try again.${waiting}`;
    if (body && msg.method === "tools/call" && QUEUEABLE.includes(msg.params?.name)) {
      if (queue.length >= MAX_QUEUE) {
        return json(200, { jsonrpc: "2.0", id: msg.id, result: { content: [{ type: "text", text: `${say} The waiting list is full (${MAX_QUEUE}).` }], isError: true } });
      }
      queue.push({ id: crypto.randomUUID(), body, at: Date.now() });
      await this.ctx.storage.put("queue", queue);
      const text = `Slidemill's phone is offline, so this is saved and runs as soon as the phone is back online ` +
        `(${queue.length} waiting). You can't see the result yet: check list_slideshows once the phone is online. ` +
        `A slideshow made later can't be referred to by id now; to save one to TikTok drafts, pass save_draft: true to create_slideshow.`;
      return json(200, { jsonrpc: "2.0", id: msg.id, result: { content: [{ type: "text", text }], isError: false } });
    }
    switch (msg.method) {
      case "initialize": {
        const cached = (await this.ctx.storage.get("initialize")) || {
          capabilities: { tools: { listChanged: false } }, serverInfo: { name: "Slidemill", version: "2.0.0" },
        };
        const asked = msg.params?.protocolVersion;
        return json(200, { jsonrpc: "2.0", id: msg.id, result: { ...cached, protocolVersion: VERSIONS.includes(asked) ? asked : VERSIONS.at(-1) } });
      }
      case "ping":
        return json(200, { jsonrpc: "2.0", id: msg.id, result: {} });
      case "tools/list": {
        const cached = await this.ctx.storage.get("tools/list");
        if (cached) return json(200, { jsonrpc: "2.0", id: msg.id, result: cached });
        return json(200, rpcError(msg.id, -32603, say));
      }
      case "tools/call":
        return json(200, { jsonrpc: "2.0", id: msg.id, result: { content: [{ type: "text", text: say }], isError: true } });
      default:
        return json(200, rpcError(msg.id, -32603, say));
    }
  }
}

// ---- Firebase Cloud Messaging auth: a service-account JWT traded for a 1-hour access token ----------

let cachedToken = null; // { token, until }

const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const b64urlText = (text) => b64url(new TextEncoder().encode(text));

async function fcmAccessToken(sa) {
  if (cachedToken && cachedToken.until > Date.now() + 60_000) return cachedToken.token;
  const now = Math.floor(Date.now() / 1000);
  const head = b64urlText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64urlText(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600,
  }));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${claims}`));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${head}.${claims}.${b64url(sig)}`,
  });
  const out = await r.json();
  if (!out.access_token) throw new Error("FCM auth failed");
  cachedToken = { token: out.access_token, until: Date.now() + (out.expires_in || 3600) * 1000 };
  return cachedToken.token;
}
