# Connect Claude to Slidemill

Slidemill is also an MCP server, both on a server and in the phone app. Add it to Claude as a custom connector, and Claude (in claude.ai, the Claude phone and desktop apps, or Claude Code) can research your library, write slideshows, look at the rendered slides, and score them, all in conversation. Each finished slideshow waits as a draft in the Slidemill app; you preview it there and tap **POST** to publish it live. Optionally, Claude also saves a copy to TikTok Studio's web drafts (those appear on tiktok.com/tiktokstudio, not in the TikTok app's Drafts).

Claude is the writer. It sends a structured plan (`SlideshowPlan`), and Slidemill finds or generates the backgrounds and renders the slides. Writing uses your Claude plan. On the phone, Claude is also the scorer, so the phone app needs no AI key at all. The server version scores with the AI key in Settings.

The phone app is built around the connector: Claude decides, and the phone renders and saves drafts. It has no dashboard, schedules or queue (see [android/README.md](../android/README.md)).

## Set up on the phone (free, no server)

The phone app's engine runs on the phone's private `127.0.0.1`, which Claude can't reach. A small relay on your own free Cloudflare account gives it a fixed https address. The phone dials out to the relay and keeps the connection open. Claude sends its requests to the relay, and the relay passes them to the phone. Everything (rendering, the library, saving drafts) runs on the phone; the relay only passes messages along.

You set it up once, from the phone's browser, in about 10 minutes:

1. Make a free Cloudflare account at cloudflare.com. It needs an email address only: no card, no ID. Open **Workers & Pages** once, which sets up your `workers.dev` address.
2. In Cloudflare, go to **My Profile → API Tokens → Create Token** and use the **Edit Cloudflare Workers** template. Copy the token. Copy your **Account ID** too: it's on the Workers & Pages overview page.
3. In your Slidemill repository on GitHub, open **Settings → Secrets and variables → Actions** and add two repository secrets: `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
4. Open the **Actions** tab, choose **Deploy Claude relay** and tap **Run workflow**. When it finishes, its summary shows your relay address, such as `https://slidemill-relay.yourname.workers.dev`.
5. In the Slidemill app's **Slidemill** tab, paste the relay address and tap **Save relay address**. It shows "✓ Connected".
6. Tap **Copy Claude link**. In Claude, open **Settings → Connectors**, add a custom connector and paste it.

Claude can reach Slidemill whenever the app is running on the phone (the "Slidemill running" notification).

**Web drafts are saved inside the call** (optional; they show on tiktok.com/tiktokstudio, not in the TikTok app). `save_draft`, or `create_slideshow` with `save_draft: true`, uploads the slides from the phone's logged-in TikTok page and makes TikTok Studio's own "Save draft" request. The answer is TikTok's draft id, or the exact reason it failed (for example, not logged in to TikTok in the app). The result is also stored on the slideshow, so `view_slideshow` and the app show it.

**Slow work doesn't time out.** Claude's apps give up on a tool call after about a minute, and generating backgrounds or uploading to TikTok can take longer on a phone. Past about 45 seconds, the tool answers with the slideshow's id, the phone finishes, and `view_slideshow` shows the outcome (the draft id or the error).

**While the phone is offline, the relay keeps a waiting list.** Requests that change something (`create_slideshow`, `import_slides`, `save_draft`, `delete_slideshow`) are saved, up to 20, and run on the phone as soon as it's back online. Claude is told the request was saved and can't see the result yet. Requests that only read (listing, viewing) answer "Slidemill's phone is offline". The phone acknowledges each saved request before running it and remembers it, so a dropped connection can't make it run twice.

The relay fits well inside Cloudflare's free plan. One Durable Object holds the phone's connection, and WebSocket hibernation means an idle connection uses no paid time.

**The connector URL is the password.** It contains a token derived from a secret that never leaves the phone. Knowing the URL lets someone use the tools, but not pose as your phone. If the URL leaks, tap **New link**: the old one stops working at once.

## Set up on a server

1. Run Slidemill on a server with a public https address (`deploy/install.sh` sets up Caddy, HTTPS and a strong `SLIDEMILL_API_KEY`).
2. In the dashboard, go to **Settings → Connect Claude** and copy the connector URL.
3. In Claude, open **Settings → Connectors**, add a custom connector and paste the URL.
4. Ask, for example: "Look at my best library posts, write 3 slideshows in that spirit, score them, and show me the best one."

The URL is `https://<your domain>/mcp/<token>`. The token is derived from `SLIDEMILL_API_KEY`, but it isn't the key itself. **The URL is the password:** whoever has it can make, delete and queue slideshows. Changing `SLIDEMILL_API_KEY` changes the URL. Without an API key, the connector is off.

## Tools

On the phone:

| Tool | What it does |
|---|---|
| `writing_guide` | What holds attention in a TikTok slideshow |
| `research_library` | Real library slideshows, best first by views vs their account's usual views |
| `add_to_library` | Import TikTok accounts or posts into the research library |
| `read_library_slides` / `save_slide_texts` | Claude reads slide text the library doesn't have yet, off the slide images |
| `create_slideshow` | Render a slideshow Claude wrote and return the slides as images; with `save_draft: true`, also save it to TikTok drafts |
| `import_slides` | Use finished slide images Claude made itself (https links, or small `data:image` URIs): stored as they are, in order; with `save_draft: true`, saved to TikTok drafts in the same call. The relay takes about 1 MB per call, so inline images must be small; links have no such limit |
| `save_draft` | Save a slideshow to TikTok Studio drafts now; answers with TikTok's draft id or why it failed |
| `schedule_slideshow` | Pick when a slideshow goes live on TikTok Studio's own schedule (15 minutes to 30 days ahead). Autopilot by default: it schedules directly, and the post goes public with no approval (at least 2 hours apart, at most 4 in 24 hours). With autopilot turned off in the app, it only proposes the time for the user's one-tap approval |
| `view_slideshow` | Details, draft status and rendered slides of one slideshow |
| `list_slideshows` | Recent slideshows with their draft status and scores |
| `delete_slideshow` | Remove a weaker version (doesn't touch TikTok drafts) |
| `predict_virality` | Score a slideshow with Claude as the reader. The first call returns the scoring rules, the slides and real library posts to rank it against blind; Claude sends its read back and Slidemill turns it into the score, attention curve and rank |
| `analytics` | The account's full analytics, the same report as the app's Analytics tab: followers and their weekly change, totals, the last 7 days against the 7 before, views per week, slideshows against videos, top posts, and every post's views, likes, comments, shares, saves, engagement, views per day and views in the last 24 hours, plus the Slidemill slideshow and score it came from |
| `my_posts` | Views, likes, comments, shares and saves of the logged-in account's recent posts. Posts made with Slidemill show their predicted score, and `prediction_check` says whether the score has tracked this account's real views (8+ scored posts, 2+ days old) |
| `best_times` | When to post for an audience in a country |
| `phone_status` | Whether the phone is logged in to TikTok, where backgrounds come from, the library size |

The server version (`app/mcp_server.py`) keeps its own set: `queue_post` queues a post through the dashboard's checks, `analytics` reads posted slideshows, and `predict_virality` uses the AI key. A public post through TikTok's official API needs the user's own privacy choice and their confirmation of TikTok's Music Usage Confirmation, so Claude has to ask you first. Claude's apps also ask before running a tool unless you've allowed it.

## How it's built

On the server, `app/mcp_server.py` uses the official MCP Python SDK (`mcp` 2.x, `MCPServer`) over Streamable HTTP, stateless with JSON responses. It's mounted at `/mcp/<token>` in the main app (`app/main.py`) and guarded against DNS rebinding for the public host.

On the phone, `android/core/.../Mcp.kt` serves the same tools over the same protocol. It handles the `initialize` handshake (protocol revisions 2024-11-05 to 2025-11-25). `Relay.kt` holds the WebSocket to the relay, reconnects with backoff, and keeps the phone awake while it answers. The relay itself is `relay/src/index.js`, a Cloudflare Worker with one Durable Object per phone. Answers are split into parts so slide previews fit Cloudflare's message size. When the phone is offline, the relay answers `initialize` and `tools/list` from its last copy, so Claude can still connect.

The server version calls Slidemill's own REST API for most tools, so validation and limits match the dashboard's. The phone version calls the engine directly: the phone has no REST API.

Tests: `tests/test_mcp.py` (server), `McpTest.kt` (phone), and `relay/test/relay.test.mjs` (`npm test` in `relay/`), which runs the relay on a local `wrangler dev` with a fake phone.
