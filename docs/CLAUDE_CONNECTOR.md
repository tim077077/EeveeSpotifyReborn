# Connect Claude to Slidemill

Slidemill is also an MCP server, both on a server and in the phone app. Add it to Claude as a custom connector, and Claude (in claude.ai, the Claude phone and desktop apps, or Claude Code) can research your library, write slideshows, look at the rendered slides, score them and queue posts, all in conversation.

Claude is the writer. It sends the same structured plan Slidemill's own AI writer produces (`SlideshowPlan` in `app/schemas.py`), and the normal pipeline finds or generates the backgrounds and renders the slides. A Claude-written slideshow looks exactly like an app-made one. Writing uses your Claude plan, not Slidemill's AI key. Scoring (`predict_virality`) still uses the AI key in Settings: one call per slideshow.

## Set up on the phone (free, no server)

The phone app's engine runs on the phone's private `127.0.0.1`, which Claude can't reach. A small relay on your own free Cloudflare account gives it a fixed https address. The phone dials out to the relay and keeps the connection open. Claude sends its requests to the relay, and the relay passes them to the phone. Everything (writing guide, rendering, scoring, posting) runs on the phone; the relay only passes messages along.

You set it up once, from the phone's browser, in about 10 minutes:

1. Make a free Cloudflare account at cloudflare.com. It needs an email address only: no card, no ID. Open **Workers & Pages** once, which sets up your `workers.dev` address.
2. In Cloudflare, go to **My Profile → API Tokens → Create Token** and use the **Edit Cloudflare Workers** template. Copy the token. Copy your **Account ID** too: it's on the Workers & Pages overview page.
3. In your Slidemill repository on GitHub, open **Settings → Secrets and variables → Actions** and add two repository secrets: `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
4. Open the **Actions** tab, choose **Deploy Claude relay** and tap **Run workflow**. When it finishes, its summary shows your relay address, such as `https://slidemill-relay.yourname.workers.dev`.
5. In the Slidemill app, go to **Settings → Connect Claude**, paste the relay address and tap **Connect**. The pill turns green ("Connected").
6. Copy the **Connector URL for Claude**. In Claude, open **Settings → Connectors**, add a custom connector and paste it.

Claude can reach Slidemill whenever the app is running on the phone (the "Slidemill running" notification).

**While the phone is offline, the relay keeps a waiting list.** Requests that change something (`create_slideshow`, `queue_post`, `delete_slideshow`) are saved, up to 20, and run on the phone as soon as it's back online. Claude is told the request was saved and can't see the result yet. Requests that only read (listing, viewing, analytics) answer "Slidemill's phone is offline". To have a slideshow made and posted later in one step, Claude passes `post` (`draft` or `publish`) to `create_slideshow`. The phone acknowledges each saved request before running it and remembers it, so a dropped connection can't make it run twice. Auto-posting never needed the relay.

**Slow renders don't time out.** Claude's apps give up on a tool call after about a minute, and finding or generating backgrounds can take longer on a phone. When rendering runs past about 40 seconds, `create_slideshow` answers with the slideshow's id, the phone carries on, and Claude checks it with `view_slideshow`.

The relay fits well inside Cloudflare's free plan. One Durable Object holds the phone's connection, and WebSocket hibernation means an idle connection uses no paid time.

**The connector URL is the password.** It contains a token derived from a secret that never leaves the phone. Knowing the URL lets someone use the tools, but not pose as your phone. If the URL leaks, tap **New connector URL**: the old one stops working at once.

## Set up on a server

1. Run Slidemill on a server with a public https address (`deploy/install.sh` sets up Caddy, HTTPS and a strong `SLIDEMILL_API_KEY`).
2. In the dashboard, go to **Settings → Connect Claude** and copy the connector URL.
3. In Claude, open **Settings → Connectors**, add a custom connector and paste the URL.
4. Ask, for example: "Look at my best library posts, write 3 slideshows in that spirit, score them, and show me the best one."

The URL is `https://<your domain>/mcp/<token>`. The token is derived from `SLIDEMILL_API_KEY`, but it isn't the key itself. **The URL is the password:** whoever has it can make, delete and queue slideshows. Changing `SLIDEMILL_API_KEY` changes the URL. Without an API key, the connector is off.

## Tools

| Tool | What it does |
|---|---|
| `writing_guide` | The rules Slidemill's own writer follows |
| `research_library` | Real library slideshows, best first by views vs their account's usual views |
| `create_slideshow` | Render a slideshow Claude wrote; returns the slides as images |
| `view_slideshow` | Details and rendered slides of one slideshow |
| `list_slideshows` | Recent slideshows with their scores |
| `predict_virality` | Score a slideshow (one AI call with Slidemill's key; none if unchanged) |
| `delete_slideshow` | Remove a weaker draft |
| `queue_post` | Queue a slideshow for TikTok, the same as the dashboard's Post button |
| `analytics` | Views, likes, comments and shares of posted slideshows |
| `best_times` | When to post for an audience in a country |

`queue_post` goes through the same checks as the dashboard. A public post through TikTok's official API needs the user's own privacy choice and their confirmation of TikTok's Music Usage Confirmation, so Claude has to ask you first. On the phone, `queue_post` posts from the phone's TikTok tab (`publish`) or hands the post to the TikTok app (`draft`). Claude's apps also ask before running a tool unless you've allowed it.

## How it's built

On the server, `app/mcp_server.py` uses the official MCP Python SDK (`mcp` 2.x, `MCPServer`) over Streamable HTTP, stateless with JSON responses. It's mounted at `/mcp/<token>` in the main app (`app/main.py`) and guarded against DNS rebinding for the public host.

On the phone, `android/core/.../Mcp.kt` serves the same tools over the same protocol. It handles the `initialize` handshake (protocol revisions 2024-11-05 to 2025-11-25). `Relay.kt` holds the WebSocket to the relay, reconnects with backoff, and keeps the phone awake while it answers. The relay itself is `relay/src/index.js`, a Cloudflare Worker with one Durable Object per phone. Answers are split into parts so slide previews fit Cloudflare's message size. When the phone is offline, the relay answers `initialize` and `tools/list` from its last copy, so Claude can still connect.

Both versions call Slidemill's own REST API for every tool except `create_slideshow` and `research_library`, so validation, limits and response shapes match the dashboard's.

Tests: `tests/test_mcp.py` (server), `McpTest.kt` (phone), and `relay/test/relay.test.mjs` (`npm test` in `relay/`), which runs the relay on a local `wrangler dev` with a fake phone.
