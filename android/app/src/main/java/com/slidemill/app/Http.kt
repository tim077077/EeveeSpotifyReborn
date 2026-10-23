package com.slidemill.app

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32

/** Blocking HTTP helpers. Call from a background thread. */
object Http {
    class ApiError(message: String) : Exception(message)

    private const val UA = "SlidemillAndroid/1.0"

    /** Call the Slidemill API (`path` starts with /api/v1/...). */
    fun api(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = (URL(Prefs.server + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer " + Prefs.key)
            setRequestProperty("User-Agent", UA)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { b -> conn.outputStream.use { it.write(b.toString().toByteArray()) } }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw ApiError("Slidemill rejected the password")
            if (code >= 400) throw ApiError("Slidemill answered $code")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Download a slide (served by Slidemill) to a local file. */
    fun download(url: String, target: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer " + Prefs.key)
        }
        try {
            if (conn.responseCode != 200) throw ApiError("couldn't download slide (${conn.responseCode})")
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

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
