package com.slidemill.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps the phone connected to the user's Slidemill relay (a free Cloudflare Worker, relay/), so
 * Claude can reach the phone's MCP server at a fixed https address while the phone has no public
 * one. The phone dials out and holds a WebSocket open; the relay hands it each request from Claude
 * and returns the answer. Only MCP goes through it: the dashboard and the REST API stay on 127.0.0.1.
 *
 * The phone's secret proves it to the relay; Claude's URL carries only a token derived from it, so
 * that URL can't be used to impersonate the phone.
 */
class Relay(private val e: Engine) {
    companion object {
        const val CHUNK = 200_000 // characters per WebSocket message (the relay joins the parts)

        /** The path segment of Claude's URL, derived from the phone's secret (same as app/mcp_server.token()). */
        fun claudeToken(secret: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            return mac.doFinal("slidemill-mcp".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        }

        /** "slidemill-relay.me.workers.dev", "https://…/" or a pasted Claude URL → "https://host". */
        fun normalize(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return ""
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            val u = java.net.URI(s)
            if (u.host.isNullOrEmpty()) throw ValidationError("That isn't a web address.")
            return "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
        }
    }

    val mcp = Mcp(e)
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS) // also notices a dead connection
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val daemon = java.util.concurrent.ThreadFactory { r -> Thread(r, "relay").apply { isDaemon = true } }
    private val work = Executors.newFixedThreadPool(3, daemon)
    private val retry = Executors.newSingleThreadScheduledExecutor(daemon)
    private val lock = Any()
    private var socket: WebSocket? = null
    private var generation = 0 // bumps on every (re)configure, so stale sockets stop reconnecting
    private var backoff = 2L
    @Volatile private var state = "off"
    @Volatile private var detail = ""
    @Volatile var handled = 0
        private set

    private fun saved(): Obj = e.db.tx { get("state", "relay")?.copyObj() } ?: obj()

    private fun secret(): String {
        saved().strOrNull("secret")?.let { stored -> runCatching { e.platform.decryptSecret(stored) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it } }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        save("secret" to e.platform.encryptSecret(fresh))
        return fresh
    }

    private fun save(vararg values: Pair<String, Any?>) = e.db.tx {
        val row = get("state", "relay") ?: insert("state", obj("id" to "relay"))
        values.forEach { (k, v) -> row[k] = v }
    }

    val url: String get() = saved().str("url")

    fun status(): Obj {
        val base = url
        return obj(
            "url" to base,
            "claude_url" to if (base.isEmpty()) null else "$base/mcp/${claudeToken(secret())}",
            "state" to if (base.isEmpty()) "off" else state,
            "detail" to detail,
            "handled" to handled,
        )
    }

    /** Set (or clear, with "") the relay address and (re)connect. */
    fun configure(raw: String): Obj {
        save("url" to normalize(raw))
        start()
        return status()
    }

    /** A new secret: the old Claude URL stops working. */
    fun newLink(): Obj {
        save("secret" to "")
        start()
        return status()
    }

    fun start(): Unit = synchronized(lock) {
        generation++
        socket?.close(1000, "reconnecting")
        socket = null
        backoff = 2
        if (url.isEmpty()) {
            state = "off"
            detail = ""
            return
        }
        connect(generation)
    }

    fun stop(): Unit = synchronized(lock) {
        generation++
        socket?.close(1000, "stopped")
        socket = null
        state = "off"
    }

    private fun connect(gen: Int) {
        val base = url
        if (base.isEmpty()) return
        state = "connecting"
        val target = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/phone"
        val req = Request.Builder().url(target).header("Authorization", "Bearer ${secret()}").build()
        val ws = http.newWebSocket(req, Listener(gen))
        socket = ws
        // A connection that never finishes opening (half-open network) is dropped and retried.
        retry.schedule({
            if (synchronized(lock) { gen == generation && socket === ws && state == "connecting" }) ws.cancel()
        }, 30, TimeUnit.SECONDS)
    }

    private fun reconnectLater(gen: Int, why: String) {
        synchronized(lock) {
            if (gen != generation) return
            state = "offline"
            detail = why
            socket = null
            val wait = backoff
            backoff = minOf(backoff * 2, 60)
            retry.schedule({ synchronized(lock) { if (gen == generation) connect(gen) } }, wait, TimeUnit.SECONDS)
        }
    }

    private val queueWork = Executors.newSingleThreadExecutor(daemon)

    /**
     * Something Claude asked for while the phone was offline (the relay kept it). Acknowledged first,
     * so the relay forgets it, and remembered, so a resend after a dropped connection can't run it twice;
     * then run one at a time. Claude sees the outcome in list_slideshows.
     */
    private fun runQueued(webSocket: WebSocket, msg: Obj) {
        val id = msg.str("id")
        val fresh = e.db.tx {
            val row = get("state", "relay_queue") ?: insert("state", obj("id" to "relay_queue", "done" to arr()))
            val done = row.strings("done")
            if (id in done) false else {
                row["done"] = (done + id).takeLast(200).toMutableList<Any?>()
                true
            }
        }
        webSocket.send(Json.write(obj("t" to "ack", "id" to id)))
        if (!fresh) return
        queueWork.submit {
            e.platform.awake { runCatching { mcp.handle("POST", msg.str("body")) }.onFailure { e.platform.log("queued request failed: $it") } }
            handled++
        }
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (synchronized(lock) { gen != generation }) {
                webSocket.close(1000, "stale")
                return
            }
            state = "connected"
            detail = ""
            backoff = 2
            e.platform.log("relay connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { Json.parseObj(text) }.getOrNull() ?: return
            if (msg.str("t") == "queued") return runQueued(webSocket, msg)
            if (msg.str("t") != "req") return
            work.submit {
                val reply = e.platform.awake {
                    runCatching { mcp.handle(msg.str("method"), msg.str("body")) }
                        .getOrElse { Mcp.Reply(500, Json.write(obj("jsonrpc" to "2.0", "id" to null, "error" to obj("code" to -32603, "message" to (it.message ?: "failed"))))) }
                }
                handled++
                val id = msg.str("id")
                val parts = reply.body.chunked(CHUNK).ifEmpty { listOf("") }
                parts.dropLast(1).forEach { webSocket.send(Json.write(obj("t" to "part", "id" to id, "data" to it))) }
                webSocket.send(Json.write(obj("t" to "res", "id" to id, "status" to reply.status, "type" to reply.contentType, "data" to parts.last())))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            reconnectLater(gen, when (code) {
                4001 -> "Replaced by another connection with this link."
                else -> "Relay closed the connection${if (reason.isNotEmpty()) ": $reason" else ""}."
            })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val why = when (response?.code) {
                404 -> "No Slidemill relay at that address (check it, or deploy the relay)."
                null -> "Can't reach the relay: ${t.message ?: t.javaClass.simpleName}"
                else -> "Relay answered ${response.code}."
            }
            reconnectLater(gen, why)
        }
    }
}
