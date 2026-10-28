// Runs tiktok-inject.js against a fake tiktok.com and checks that "Save draft" sends what TikTok
// Studio sends (shape taken from a real captured request) and never touches the publish endpoint.
// Run: node extension/test/draft.test.mjs
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

// TikTok Studio's own "Save draft" body for a 2-photo carousel (captured; ids replaced).
const CAPTURED = {
  draft: {
    web_feature_common_info: {
      aigc_info: { aigc_label_type: 0 }, org_game_post_use_store_region: false, vedit_common_info: {},
      privacy_setting_info: { allow_comment: 1, allow_duet: 1, allow_stitch: 1, allow_content_reuse: 1, allow_ai_remix: 2, visibility_type: 0 },
      tcm_params: '{"commerce_toggle_info":{}}', anchors: [],
    },
    basic_info: {
      creation_id: "L7FLEdMz2ett70H3G431w", enter_post_page_from: 1,
      media_draft_info: {
        aweme_type: 150,
        image_post_content: {
          title: "",
          images: [{ id: "file_1_0", uri: "tos/a", width_px: 2160, height_px: 2700 }, { id: "file_1_1", uri: "tos/b", width_px: 2160, height_px: 2700 }],
          cover: { id: "file_1_0", uri: "tos/a", width_px: 2160, height_px: 2700 },
        },
      },
    },
    draft_id: "",
    web_video_param_list: [{ single_post_feature_info: { text: "", text_extra: [], markup_text: '{"blocks":[],"entityMap":{}}' } }],
  },
};

// Every key path in an object, arrays collapsed to their first item ("a.b[].c").
const paths = (v, p = "") => {
  if (Array.isArray(v)) return v.length ? paths(v[0], p + "[]") : [p + "[]"];
  if (v && typeof v === "object") return Object.entries(v).flatMap(([k, x]) => [p + "." + k, ...paths(x, p + "." + k)]);
  return [];
};

const calls = [];
const listeners = [];
let commits = 0;
const reply = (body) => ({ json: async () => body, arrayBuffer: async () => new ArrayBuffer(0) });
const fetch = async (url, init = {}) => {
  const u = new URL(url);
  calls.push({ path: u.pathname, query: u.search, body: init.body });
  if (u.pathname === "/api/v1/video/upload/auth/") return reply({ video_token_v5: { access_key_id: "AK", secret_acess_key: "SK", session_token: "T" } });
  if (u.searchParams.get("Action") === "ApplyImageUpload") {
    return reply({ Result: { UploadAddress: { StoreInfos: [{ StoreUri: "tos/s" + commits, Auth: "x:y:z" }], UploadHosts: ["up.example"], SessionKey: "k" + commits } } });
  }
  if (u.searchParams.get("Action") === "CommitImageUpload") {
    const n = commits++;
    return reply({ Result: { PluginResult: [{ ImageUri: "tos/img" + n, ImageWidth: 1080, ImageHeight: 1350 }] } });
  }
  if (u.pathname === "/api/v1/draft/create_update/") return reply({ draft: { draft: { draft_id: "46508480535" } }, status_code: 0, status_msg: "" });
  throw new Error("unexpected request " + url);
};

class XMLHttpRequest {
  open() {} setRequestHeader() {}
  send() { this.status = 200; this.responseText = '{"code":2000}'; setTimeout(() => this.onload(), 0); }
}
class Image { set src(_) { setTimeout(() => this.onerror(), 0); } }

const win = {
  fetch, XMLHttpRequest, Image, location: { host: "www.tiktok.com" },
  addEventListener: (_, f) => listeners.push(f), removeEventListener() {}, postMessage() {},
  console: { log() {}, warn() {} },
  Blob, URL, URLSearchParams, TextEncoder, atob, btoa, crypto, setTimeout, clearTimeout, Uint8Array, Uint32Array, ArrayBuffer, JSON, Date, Math, Promise,
};
const ctx = vm.createContext(win);
const page = vm.runInContext("window = globalThis", ctx); // what the script sees as window
vm.runInContext(readFileSync(new URL("../tiktok-inject.js", import.meta.url), "utf8"), ctx);
const png = new Uint8Array([137, 80, 78, 71]);

// The page tells Slidemill it can save drafts.
const hello = new Promise((resolve) => (win.postMessage = (m) => m.__vbtt === "helloResult" && resolve(m.result)));
listeners.forEach((f) => f({ source: page, data: { __vbtt: "hello", id: 1 } }));
assert.equal((await hello).draft, true);

// A draft job: uploads both slides, then one Save draft call and no publish call.
const done = new Promise((resolve) => (win.postMessage = (m) => m.__vbtt === "jobResult" && resolve(m.result)));
const caption = "3 sleep fixes that work\nSave this #sleep #sleeptips";
listeners.forEach((f) => f({ source: page, data: { __vbtt: "job", id: 2, job: { id: "p1", action: "draft", title: "Sleep fixes", caption, images: [png, png] } } }));
const result = await done;
assert.equal(result.ok, true, result.error);
assert.equal(result.draftId, "46508480535"); // the app reports TikTok's draft id back to Claude
assert.ok(!calls.some((c) => c.path.includes("/project/post/")), "a draft must never call the publish endpoint");
const saves = calls.filter((c) => c.path === "/api/v1/draft/create_update/");
assert.equal(saves.length, 1);
assert.equal(saves[0].query, "?aid=1988"); // signing params are added by TikTok's page

const sent = JSON.parse(saves[0].body);
// Same fields as TikTok Studio (the captured caption had no hashtags, so text_extra's items are checked below).
const fields = (v) => paths(v).filter((p) => !p.includes("text_extra[]")).sort();
assert.deepEqual(fields(sent), fields(CAPTURED));
const content = sent.draft.basic_info.media_draft_info.image_post_content;
assert.equal(content.title, "Sleep fixes");
assert.deepEqual(content.images.map((i) => i.uri), ["tos/img0", "tos/img1"]);
assert.deepEqual(content.cover, content.images[0]);
assert.equal(sent.draft.basic_info.creation_id.length, 21);
const text = sent.draft.web_video_param_list[0].single_post_feature_info;
assert.equal(text.text, caption);
assert.deepEqual(text.text_extra.map((t) => t.hashtag_name), ["sleep", "sleeptips"]);
assert.deepEqual(JSON.parse(text.markup_text).blocks.map((b) => b.text), ["3 sleep fixes that work", "Save this #sleep #sleeptips"]);

// TikTok refusing the draft is reported as a failure, not as success.
win.fetch = async (url, init) => (new URL(url).pathname === "/api/v1/draft/create_update/" ? reply({ status_code: 8, status_msg: "login" }) : fetch(url, init));
const failed = new Promise((resolve) => (win.postMessage = (m) => m.__vbtt === "jobResult" && resolve(m.result)));
listeners.forEach((f) => f({ source: page, data: { __vbtt: "job", id: 3, job: { id: "p2", action: "draft", title: "x", caption: "", images: [png] } } }));
const bad = await failed;
assert.equal(bad.ok, false);
assert.match(bad.error, /save draft failed/);

// A scheduled publish: TikTok Studio's Schedule is schedule_time (unix seconds) on the post request,
// after one schedule ack (shape from a real scheduled photo post, HAR).
const posts = [];
const acks = [];
const postQueries = [];
win.fetch = async (url, init) => {
  const u = new URL(url);
  if (u.pathname === "/api/v1/post_schedule/ack/") { acks.push(u.search); return reply({ status_code: 0 }); }
  if (u.pathname === "/tiktok/web/project/post/v1/") {
    posts.push(JSON.parse(init.body));
    postQueries.push(u.searchParams);
    return reply({ status_code: 0, project_id: "pr1", single_post_resp_list: [{ item_id: "7500000000000000001" }] });
  }
  return fetch(url, init);
};
const at = (Math.floor(Date.now() / 1000 / 300) + 36) * 300; // 3 hours ahead, on a 5-minute boundary like Studio
const scheduled = new Promise((resolve) => (win.postMessage = (m) => m.__vbtt === "jobResult" && resolve(m.result)));
listeners.forEach((f) => f({ source: page, data: { __vbtt: "job", id: 4, job: { id: "p3", action: "publish", title: "t", caption: "c #x", images: [png], schedule: at } } }));
const sch = await scheduled;
assert.equal(sch.ok, true, sch.error);
assert.equal(sch.itemId, "7500000000000000001");
assert.equal(sch.scheduleTime, at);
assert.equal(posts[0].feature_common_info_list[0].schedule_time, at);
assert.deepEqual(Object.keys(posts[0].feature_common_info_list[0]).slice(0, 3), ["aigc_info", "org_game_post_use_store_region", "schedule_time"]);
assert.deepEqual(acks, ["?ack_type=2&aid=1988"]);
assert.ok(postQueries[0].get("tz_name"), "the post carries the browser's time zone, like Studio");

// Too soon (the upload ate the margin) is pushed out to what TikTok accepts; no schedule means now.
const soon = new Promise((resolve) => (win.postMessage = (m) => m.__vbtt === "jobResult" && resolve(m.result)));
listeners.forEach((f) => f({ source: page, data: { __vbtt: "job", id: 5, job: { id: "p4", action: "publish", title: "t", caption: "", images: [png], schedule: Math.floor(Date.now() / 1000) + 60 } } }));
const soonAt = (await soon).scheduleTime;
assert.ok(soonAt >= Math.floor(Date.now() / 1000) + 15 * 60);
assert.equal(soonAt % 300, 0); // Studio's 5-minute steps
const now = new Promise((resolve) => (win.postMessage = (m) => m.__vbtt === "jobResult" && resolve(m.result)));
listeners.forEach((f) => f({ source: page, data: { __vbtt: "job", id: 6, job: { id: "p5", action: "publish", title: "t", caption: "", images: [png] } } }));
assert.equal((await now).scheduleTime, 0);
assert.ok(!("schedule_time" in posts[2].feature_common_info_list[0]));
assert.equal(acks.length, 2); // posting now needs no ack

console.log("tiktok-inject: draft and schedule checks passed");

// "List my posts": pages TikTok Studio's content list by cursor and keeps only published posts.
const listCalls = [];
win.fetch = async (url, init = {}) => {
  const u = new URL(url);
  if (u.pathname !== "/tiktok/creator/manage/item_list/v1/") throw new Error("unexpected request " + url);
  if (init.method === "HEAD") return { headers: { get: (h) => (h === "x-ware-csrf-token" ? "0,tok123,86370000,success" : null) } };
  listCalls.push({ query: u.searchParams, headers: init.headers, body: JSON.parse(init.body) });
  const page1 = { cursor: "50", has_more: true, status_code: 0, item_list: [
    { item_id: "9", desc: "scheduled one", status: 141, item_type: 2, play_count: "0" },
    { item_id: "8", desc: "5 money tips #money", status: 102, item_type: 2, post_time: "1761500000", play_count: "1200", like_count: "40", comment_count: "3", share_count: "2", favorite_count: "7", visibility: 1, photo_list: ["a", "b", "c"] },
  ] };
  const page2 = { cursor: "51", has_more: false, status_code: 0, item_list: [
    { item_id: "7", desc: "a video", status: 102, item_type: 1, create_time: "1761400000", play_count: "300", duration: "15400", visibility: 1 },
  ] };
  return reply(listCalls.length === 1 ? page1 : page2);
};
const listed = new Promise((resolve) => (win.postMessage = (m) => m.__vbtt === "listMineResult" && resolve(m.result)));
listeners.forEach((f) => f({ source: page, data: { __vbtt: "listMine", id: 5, max: 100 } }));
const mine = JSON.parse(JSON.stringify(await listed)); // arrays from the page's realm
assert.equal(mine.ok, true, mine.error);
assert.deepEqual(mine.posts.map((p) => p.id), ["8", "7"]); // the scheduled post isn't counted
assert.deepEqual([mine.posts[0].views, mine.posts[0].likes, mine.posts[0].saves, mine.posts[0].slides, mine.posts[0].is_slideshow], [1200, 40, 7, 3, true]);
assert.deepEqual([mine.posts[1].posted_at, mine.posts[1].is_slideshow, mine.posts[1].duration_s], [1761400000, false, 15]);
assert.equal(listCalls.length, 2);
assert.deepEqual(listCalls.map((c) => c.body.cursor), [0, 50]);
assert.equal(listCalls[0].query.get("app_name"), "tiktok_creator_center");
assert.equal(listCalls[0].headers["tt-csrf-token"], "tok123");

// A refused listing is an error, not "no posts".
win.fetch = async (url, init = {}) => (init.method === "HEAD" ? { headers: { get: () => null } } : reply({ status_code: 8, status_msg: "login" }));
const refused = new Promise((resolve) => (win.postMessage = (m) => m.__vbtt === "listMineResult" && resolve(m.result)));
listeners.forEach((f) => f({ source: page, data: { __vbtt: "listMine", id: 6 } }));
assert.equal((await refused).ok, false);
console.log("listMine: ok");

// Another account's posts: the profile's own post list, paged by cursor, stopping after the page with a post the library has.
const userCalls = [];
win.fetch = async (url) => {
  const u = new URL(url);
  if (u.pathname !== "/api/post/item_list/") throw new Error("unexpected request " + url);
  userCalls.push(u.searchParams);
  const pages = {
    "0": { hasMore: true, cursor: "c1", statusCode: 0, itemList: [{ id: "30" }, { id: "29" }] },
    c1: { hasMore: true, cursor: "c2", statusCode: 0, itemList: [{ id: "28" }, { id: "27" }, { id: "26" }] },
  };
  return { ok: true, text: async () => JSON.stringify(pages[u.searchParams.get("cursor")]) };
};
const ask = (data) => new Promise((resolve) => {
  win.postMessage = (m) => m.__vbtt === "listUserResult" && resolve(JSON.parse(JSON.stringify(m.result)));
  listeners.forEach((f) => f({ source: page, data: { __vbtt: "listUser", id: 7, pauseMs: 0, ...data } }));
});
const theirs = await ask({ secUid: "MS4x", max: 50, known: ["27"] });
assert.equal(theirs.ok, true, theirs.error);
assert.deepEqual(theirs.items.map((i) => i.id), ["30", "29", "28", "27", "26"]); // the page with a known post, then stop
assert.equal(theirs.stoppedAtKnown, true);
assert.deepEqual(userCalls.map((q) => q.get("cursor")), ["0", "c1"]);
assert.equal(userCalls[0].get("secUid"), "MS4x");
userCalls.length = 0;
assert.deepEqual((await ask({ secUid: "MS4x", max: 2 })).items.map((i) => i.id), ["30", "29"]);
assert.equal(userCalls.length, 1); // no second page once max is reached
win.fetch = async () => ({ ok: true, text: async () => "" });
assert.equal((await ask({ secUid: "MS4x" })).ok, false); // an empty answer is an error, so the app falls back
console.log("listUser: ok");

// Comments: the auto-reply's read and write. The publish shape is a reply typed by hand (captured HAR):
// POST /api/comment/publish/?aweme_id=…&reply_id=…&text=…&text_extra=[], reply_to_reply_id for a reply.
const commentCalls = [];
const pagesOf = {
  "0": { status_code: 0, has_more: true, cursor: "77", comments: [
    { cid: "c1", text: "PLAN please 🙏", create_time: "1793214000", digg_count: 3, reply_id: "0", reply_comment_total: 1,
      user: { unique_id: "gigel", nickname: "Gigel" },
      reply_comment: [{ cid: "c1r", text: "pln", create_time: "1793214100", user: { unique_id: "ana" }, label_type: 1 }] },
    { cid: "c2", text: "not interested", create_time: "1793213000", user: { uniqueId: "marius" } },
  ] },
  "77": { status_code: 0, has_more: false, cursor: "77", comments: [{ cid: "c3", text: "plan!!", create_time: "1793212000", user: { unique_id: "ioana" } }] },
};
win.fetch = async (url, init = {}) => {
  const u = new URL(url);
  if (u.pathname !== "/api/comment/list/") throw new Error("unexpected request " + url);
  commentCalls.push({ query: u.searchParams, credentials: init.credentials });
  return { ok: true, text: async () => JSON.stringify(pagesOf[u.searchParams.get("cursor")]) };
};
const askComments = (data) => new Promise((resolve) => {
  win.postMessage = (m) => m.__vbtt === "commentsResult" && resolve(JSON.parse(JSON.stringify(m.result)));
  listeners.forEach((f) => f({ source: page, data: { __vbtt: "comments", id: 8, pauseMs: 0, ...data } }));
});
const listed2 = await askComments({ itemId: "7535824345877220630", max: 3 });
assert.equal(listed2.ok, true, listed2.error);
assert.deepEqual(listed2.comments.map((c) => c.cid), ["c1", "c1r", "c2"]); // the reply comes with its root's comments
assert.deepEqual(listed2.comments.map((c) => c.author), ["gigel", "ana", "marius"]); // unique_id and uniqueId alike
assert.deepEqual([listed2.comments[0].creator, listed2.comments[1].creator, listed2.comments[0].root_cid], [false, true, ""]);
assert.equal(listed2.comments[1].root_cid, "c1"); // a reply points at the comment it answers
assert.equal(commentCalls[0].query.get("aweme_id"), "7535824345877220630");
assert.equal(commentCalls[0].query.get("count"), "50");
assert.equal(commentCalls[0].credentials, "include");
assert.equal(commentCalls.length, 1); // max 3 reached inside the first page: no second request
assert.deepEqual((await askComments({ itemId: "7535824345877220630", max: 60 })).comments.length, 4);
assert.deepEqual(commentCalls.map((c) => c.query.get("cursor")), ["0", "0", "77"]);
win.fetch = async () => ({ ok: true, text: async () => "" });
assert.equal((await askComments({ itemId: "75", max: 10 })).ok, false); // empty answer = error, not "no comments"
win.fetch = async () => ({ ok: true, text: async () => JSON.stringify({ status_code: 8, status_msg: "login" }) });
assert.equal((await askComments({ itemId: "75", max: 10 })).ok, false);

const published = [];
win.fetch = async (url, init = {}) => {
  const u = new URL(url);
  if (u.pathname !== "/api/comment/publish/") throw new Error("unexpected request " + url);
  published.push({ query: u.searchParams, method: init.method, headers: init.headers, body: init.body });
  return { json: async () => ({ comment: { cid: "newcid1", text: u.searchParams.get("text"), create_time: 1793215000 } }) };
};
const askReply = (data) => new Promise((resolve) => {
  win.postMessage = (m) => m.__vbtt === "replyCommentResult" && resolve(JSON.parse(JSON.stringify(m.result)));
  listeners.forEach((f) => f({ source: page, data: { __vbtt: "replyComment", id: 9, ...data } }));
});
const answered = await askReply({ itemId: "7535", cid: "c1", text: "Ți-am trimis planul ✅" });
assert.equal(answered.ok, true, answered.error);
assert.equal(answered.cid, "newcid1");
assert.equal(published[0].method, "POST");
assert.equal(published[0].headers["content-type"], "application/x-www-form-urlencoded");
assert.equal(published[0].query.get("aweme_id"), "7535");
assert.equal(published[0].query.get("reply_id"), "c1");
assert.equal(published[0].query.get("text"), "Ți-am trimis planul ✅");
assert.equal(published[0].query.get("text_extra"), "[]");
assert.equal(published[0].query.get("reply_to_reply_id"), null); // answering a top-level comment
assert.match(published[0].body, /reply_id=c1/);
// Answering a reply threads under the root, exactly as the web client does (HAR).
await askReply({ itemId: "7535", cid: "c1r", rootCid: "c1", text: "ok" });
assert.equal(published[1].query.get("reply_id"), "c1");
assert.equal(published[1].query.get("reply_to_reply_id"), "c1r");
assert.equal((await askReply({ itemId: "7535", cid: "c1", text: "   " })).ok, false); // an empty reply is refused here
win.fetch = async () => ({ json: async () => ({ status_code: 10000, status_msg: "rate limit" }) });
const rejected = await askReply({ itemId: "7535", cid: "c1", text: "hi" });
assert.equal(rejected.ok, false);
assert.match(rejected.error, /refused the reply/);

const profiles = [];
win.fetch = async (url) => {
  const u = new URL(url);
  if (u.pathname !== "/api/user/detail/") throw new Error("unexpected request " + url);
  profiles.push(u.searchParams.get("uniqueId"));
  return { json: async () => (u.searchParams.get("uniqueId") === "gigel"
    ? { userInfo: { user: { uniqueId: "gigel", privateAccount: true } } }
    : { statusCode: 10221, userInfo: {} }) };
};
const askPrivate = (who) => new Promise((resolve) => {
  win.postMessage = (m) => m.__vbtt === "userPrivateResult" && resolve(JSON.parse(JSON.stringify(m.result)));
  listeners.forEach((f) => f({ source: page, data: { __vbtt: "userPrivate", id: 10, uniqueId: who } }));
});
const priv = await askPrivate("@gigel");
assert.deepEqual([priv.ok, priv.private, priv.username], [true, true, "gigel"]);
assert.deepEqual(profiles, ["gigel"]); // the @ is stripped before asking
assert.equal((await askPrivate("marius")).ok, false); // a profile that didn't come back is an error, not "not private"
console.log("comments + reply: ok");
