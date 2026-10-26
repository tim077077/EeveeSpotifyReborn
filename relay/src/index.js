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
  }

  async fetch(request) {
    if (new URL(request.url).pathname === "/phone") return this.connectPhone();
    if (request.method !== "POST") return json(405, rpcError(null, -32000, "Method not allowed: this server only answers POST."), { allow: "POST" });

    const body = await request.text();
    if (body.length > MAX_BODY) return json(413, rpcError(null, -32600, "Request too large"));
    let msg = null;
    try { msg = JSON.parse(body); } catch { return json(400, rpcError(null, -32700, "Parse error")); }

    // The newest open connection (a replaced one can still be closing).
    const phone = this.ctx.getWebSockets().filter((ws) => ws.readyState === WebSocket.OPEN).at(-1);
    const reply = phone ? await this.ask(phone, body) : null;
    if (!reply) return this.offline(msg);

    // Remember what the phone says about itself, so Claude can still connect while it's offline.
    if (reply.status === 200 && msg && (msg.method === "initialize" || msg.method === "tools/list")) {
      try {
        const out = JSON.parse(reply.data);
        if (out.result) await this.ctx.storage.put(msg.method, out.result);
      } catch { /* not cacheable */ }
    }
    return new Response(reply.status === 202 ? null : reply.data, { status: reply.status, headers: { "content-type": reply.type || "application/json" } });
  }

  connectPhone() {
    // One phone per link: a new connection replaces the old one.
    for (const old of this.ctx.getWebSockets()) {
      try { old.close(4001, "replaced"); } catch { /* already gone */ }
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
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
  async offline(msg) {
    if (!msg || Array.isArray(msg) || msg.id === undefined || !msg.method) return new Response(null, { status: 202 });
    const say = "Slidemill's phone is offline. Open the Slidemill app on the phone (and keep it running), then try again.";
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
