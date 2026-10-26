package com.slidemill.app

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32

/** Native CDN uploads for the poster. Call from a background thread. */
object Http {
    private val CDN_HOSTS = listOf(".tiktokcdn.com", ".tiktokcdn-eu.com", ".byteimg.com")

    /**
     * Upload image bytes to TikTok's CDN the way the extension's background worker does,
     * including the Origin/Referer the CDN requires. Only TikTok CDN hosts are allowed.
     */
    fun uploadToCdn(url: String, crc: String, storageU: String, b64: String): JSONObject {
        val target = URL(url)
        require(target.protocol == "https" && CDN_HOSTS.any { target.host.endsWith(it) }) { "refusing upload to ${target.host}" }
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val conn = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setFixedLengthStreamingMode(bytes.size) // a real Content-Length, like TikTok's own uploader
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Content-CRC32", crc.ifEmpty { crc32(bytes) })
            setRequestProperty("Content-Disposition", "attachment; filename=\"undefined\"")
            setRequestProperty("Origin", "https://www.tiktok.com")
            setRequestProperty("Referer", "https://www.tiktok.com/")
            if (storageU.isNotEmpty()) setRequestProperty("X-Storage-U", storageU)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            return JSONObject().put("json", json).put("status", code).put("text", text.take(300)).put("sentBytes", bytes.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun crc32(bytes: ByteArray): String = CRC32().apply { update(bytes) }.value.toString(16).padStart(8, '0')
}
