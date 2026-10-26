// Same derivation as the phone (Relay.claudeToken) and the server (app/mcp_server.token).
export async function claudeToken(secret) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode("slidemill-mcp")));
  return [...sig].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 32);
}
