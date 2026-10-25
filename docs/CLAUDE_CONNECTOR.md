# Connect Claude to Slidemill

Slidemill's server version is also an MCP server. Add it to Claude as a custom connector, and Claude (in claude.ai, the Claude phone and desktop apps, or Claude Code) can research your library, write slideshows, look at the rendered slides, score them and queue posts, all in conversation.

Claude is the writer. It sends the same structured plan Slidemill's own AI writer produces (`SlideshowPlan` in `app/schemas.py`), and the normal pipeline finds or generates the backgrounds and renders the slides. A Claude-written slideshow looks exactly like an app-made one. Writing uses your Claude plan, not Slidemill's AI key. Scoring (`predict_virality`) still uses the AI key in Settings: one call per slideshow.

## Set up

1. Run Slidemill on a server with a public https address (`deploy/install.sh` sets up Caddy, HTTPS and a strong `SLIDEMILL_API_KEY`). Claude can't reach a phone or a computer behind a home router.
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

`queue_post` goes through the same checks as the dashboard. A public post through TikTok's official API needs the user's own privacy choice and their confirmation of TikTok's Music Usage Confirmation, so Claude has to ask you first. Claude's apps also ask before running a tool unless you've allowed it.

## How it's built

`app/mcp_server.py` uses the official MCP Python SDK (`mcp` 2.x, `MCPServer`) over Streamable HTTP, stateless with JSON responses. It's mounted at `/mcp/<token>` in the main app (`app/main.py`) and guarded against DNS rebinding for the public host. Every tool except `create_slideshow` and `research_library` calls Slidemill's own REST API in-process, so validation, limits and response shapes match the dashboard's.

The phone-only app doesn't serve the connector: its engine lives on the phone's private `127.0.0.1`, which Claude can't reach.
