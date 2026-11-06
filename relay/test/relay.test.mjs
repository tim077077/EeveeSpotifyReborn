// End-to-end test of the relay on a local `wrangler dev`: a fake phone connects, a fake Claude calls.
// Run: npm test (from relay/).
import { spawn } from "node:child_process";
import assert from "node:assert/strict";
import { claudeToken } from "../src/token.js";

const PORT = 8797;
const BASE = `http://127.0.0.1:${PORT}`;
const SECRET = "ab".repeat(32);

async function waitUp() {
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(BASE + "/")).ok) return; } catch { /* starting */ }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error("wrangler dev didn't start");
}

function phone(secret, answer, { ack = true, hello = null } = {}) {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/phone`, { headers: { authorization: `Bearer ${secret}` } });
  ws.queued = [];
  ws.asleep = false; // a v2 phone whose link died: it stops answering
  ws.onmessage = async (ev) => {
    if (ws.asleep) return;
    const m = JSON.parse(ev.data);
    if (m.t === "pong") return ws.pongs = (ws.pongs || 0) + 1;
    if (m.t === "ping") return ws.send(JSON.stringify({ t: "pong" }));
    if (m.t === "queued") {
      ws.queued.push(JSON.parse(m.body).params.name);
      if (ack) ws.send(JSON.stringify({ t: "ack", id: m.id }));
      return;
    }
    const { status, data } = await answer(JSON.parse(m.body));
    // Split like the phone does, to prove the relay joins the parts.
    const text = data === null ? "" : JSON.stringify(data);
    const half = Math.floor(text.length / 2);
    if (half) ws.send(JSON.stringify({ t: "part", id: m.id, data: text.slice(0, half) }));
    ws.send(JSON.stringify({ t: "res", id: m.id, status, type: "application/json", data: text.slice(half) }));
  };
  return new Promise((resolve, reject) => {
    ws.onopen = () => { if (hello) ws.send(JSON.stringify({ t: "hello", ...hello })); setTimeout(() => resolve(ws), hello ? 200 : 0); };
    ws.onerror = reject;
  });
}

const rpc = (url, method, params = {}, id = 1, headers = {}) =>
  fetch(url, { method: "POST", headers: { "content-type": "application/json", accept: "application/json, text/event-stream", ...headers }, body: JSON.stringify({ jsonrpc: "2.0", id, method, params }) });

const dev = spawn("npx", ["wrangler", "dev", "--port", String(PORT), "--ip", "127.0.0.1", "--var", "FCM_FAKE:1"], { cwd: new URL("..", import.meta.url).pathname, stdio: ["ignore", "pipe", "pipe"], detached: true });
let log = "";
dev.stdout.on("data", (d) => (log += d));
dev.stderr.on("data", (d) => (log += d));

try {
  await waitUp();
  const token = await claudeToken(SECRET);
  assert.match(token, /^[0-9a-f]{32}$/);
  const url = `${BASE}/mcp/${token}`;

  // Nobody connected yet: Claude can still initialize, and a tool call says the phone is offline.
  let r = await (await rpc(url, "initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "t", version: "1" } })).json();
  assert.equal(r.result.protocolVersion, "2025-06-18");
  r = await (await rpc(url, "tools/call", { name: "list_slideshows", arguments: {} })).json();
  assert.equal(r.result.isError, true);
  assert.match(r.result.content[0].text, /offline/);

  // Changes asked for while offline wait in the relay; reads don't.
  const call = (name, args = {}) => rpc(url, "tools/call", { name, arguments: args }).then((x) => x.json());
  r = await call("create_slideshow", { plan: { title: "a" }, save_draft: true });
  assert.equal(r.result.isError, false);
  assert.match(r.result.content[0].text, /saved and runs as soon as the phone is back online \(1 waiting\)/);
  r = await call("delete_slideshow", { slideshow_id: "x" });
  assert.match(r.result.content[0].text, /\(2 waiting\)/);
  r = await call("list_slideshows");
  assert.match(r.result.content[0].text, /2 earlier request\(s\) are waiting/);

  // A phone that drops before acknowledging gets them again; once acknowledged they're gone.
  const flaky = await phone(SECRET, () => null, { ack: false });
  await new Promise((r) => setTimeout(r, 500));
  assert.deepEqual(flaky.queued, ["create_slideshow", "delete_slideshow"]);
  flaky.close();
  await new Promise((r) => setTimeout(r, 300));
  const steady = await phone(SECRET, () => null);
  await new Promise((r) => setTimeout(r, 500));
  assert.deepEqual(steady.queued, ["create_slideshow", "delete_slideshow"]);
  steady.close();
  await new Promise((r) => setTimeout(r, 300));
  const again = await phone(SECRET, () => null);
  await new Promise((r) => setTimeout(r, 500));
  assert.deepEqual(again.queued, []);
  again.close();
  await new Promise((r) => setTimeout(r, 300));

  // A bad secret is refused; GET is not allowed; a wrong token is a separate, empty room.
  await assert.rejects(phone("nope", () => null));
  assert.equal((await fetch(url)).status, 405);
  assert.equal((await rpc(url, "ping", {}, 1, { origin: "https://evil.example" })).status, 403);

  // The phone connects and answers; big answers come back whole.
  const big = "x".repeat(600_000);
  const ws = await phone(SECRET, async (msg) => {
    if (msg.method === "tools/list") return { status: 200, data: { jsonrpc: "2.0", id: msg.id, result: { tools: [{ name: "list_slideshows", inputSchema: { type: "object" } }] } } };
    if (msg.method === "tools/call") return { status: 200, data: { jsonrpc: "2.0", id: msg.id, result: { content: [{ type: "text", text: big }], isError: false } } };
    if (!("id" in msg)) return { status: 202, data: null };
    return { status: 200, data: { jsonrpc: "2.0", id: msg.id, result: {} } };
  });
  r = await (await rpc(url, "tools/list")).json();
  assert.equal(r.result.tools[0].name, "list_slideshows");
  r = await (await rpc(url, "tools/call", { name: "list_slideshows", arguments: {} }, 7)).json();
  assert.equal(r.id, 7);
  assert.equal(r.result.content[0].text.length, big.length);
  const note = await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }) });
  assert.equal(note.status, 202);

  // The phone's link check after a sleep: one ping, one pong.
  ws.send(JSON.stringify({ t: "ping" }));
  for (let i = 0; i < 50 && !ws.pongs; i++) await new Promise((r) => setTimeout(r, 100));
  assert.equal(ws.pongs, 1);


  const many = await Promise.all([1, 2, 3, 4].map((i) => rpc(url, "ping", {}, i).then((x) => x.json())));
  assert.deepEqual(many.map((x) => x.id), [1, 2, 3, 4]);

  // The phone goes away: the tool list is remembered, calls say offline.
  ws.close();
  await new Promise((r) => setTimeout(r, 500));
  r = await (await rpc(url, "tools/list")).json();
  assert.equal(r.result.tools[0].name, "list_slideshows");
  r = await (await rpc(url, "tools/call", { name: "list_slideshows", arguments: {} })).json();
  assert.match(r.result.content[0].text, /offline/);

  // A v2 phone with push: alive, it answers the relay's ping and the call goes through.
  const answer = async (msg) => ({ status: 200, data: { jsonrpc: "2.0", id: msg.id, result: { from: "phone" } } });
  const v2 = await phone(SECRET, answer, { hello: { v: 2, push: "fcm-token" } });
  r = await (await rpc(url, "ping", {}, 21)).json();
  assert.equal(r.result.from, "phone");
  // Its link died while the screen was off: the relay gets no pong, wakes it by push, and the woken
  // phone's new connection answers the same call.
  v2.asleep = true;
  const late = rpc(url, "ping", {}, 22).then((x) => x.json());
  await new Promise((res) => setTimeout(res, 4_500)); // the probe gives up after 4 s
  const woken = await phone(SECRET, answer, { hello: { v: 2, push: "fcm-token" } });
  r = await late;
  assert.equal(r.id, 22);
  assert.equal(r.result.from, "phone");
  // No push token: an unanswered phone is offline, as before (and the token is cleared for the next run).
  woken.send(JSON.stringify({ t: "hello", v: 2, push: "" }));
  await new Promise((res) => setTimeout(res, 200));
  woken.asleep = true;
  r = await (await rpc(url, "tools/call", { name: "list_slideshows", arguments: {} }, 23)).json();
  assert.match(r.result.content[0].text, /offline/);
  woken.close();

  console.log("relay: all checks passed");
} catch (e) {
  console.error(log.slice(-3000));
  throw e;
} finally {
  process.kill(-dev.pid, "SIGTERM"); // wrangler and its workerd
}
