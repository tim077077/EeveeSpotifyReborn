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

console.log("tiktok-inject: draft checks passed");
