package com.slidemill.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class NetResponse(val status: Int, val body: ByteArray, val headers: Map<String, String>, val url: String) {
    val text: String get() = String(body, Charsets.UTF_8)
    fun json(): Any? = Json.parse(text)
    fun obj(): Obj = Json.parse(text) as? Obj ?: throw JsonError("expected a JSON object")
    fun header(name: String): String = headers[name.lowercase()] ?: ""
}

/** Plain HttpURLConnection client (works the same on Android and the JVM). */
object Net {
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    fun query(params: Map<String, Any?>): String = params.entries.filter { it.value != null }.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value.toString(), "UTF-8")
    }

    fun withQuery(url: String, params: Map<String, Any?>): String {
        if (params.isEmpty()) return url
        return url + (if ('?' in url) "&" else "?") + query(params)
    }

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutSec: Int = 60,
        followRedirects: Boolean = true,
    ): NetResponse {
        var current = url
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 20_000
                conn.readTimeout = timeoutSec * 1000
                conn.instanceFollowRedirects = false
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                if (body != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val status = conn.responseCode
                if (followRedirects && status in 300..399 && redirects < 8) {
                    val location = conn.getHeaderField("Location")
                    if (location != null) {
                        current = URL(URL(current), location).toString()
                        redirects++
                        continue
                    }
                }
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val bytes = stream?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toByteArray() } ?: ByteArray(0)
                val hdrs = HashMap<String, String>()
                conn.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) hdrs[k.lowercase()] = v.joinToString(",") }
                return NetResponse(status, bytes, hdrs, current)
            } catch (e: IOException) {
                throw NetError("${hostOf(current)}: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                conn.disconnect()
            }
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutSec: Int = 60) =
        request("GET", url, headers, null, timeoutSec)

    fun postJson(url: String, payload: Any?, headers: Map<String, String> = emptyMap(), timeoutSec: Int = 180): NetResponse =
        request("POST", url, headers + ("Content-Type" to "application/json"), Json.write(payload).toByteArray(), timeoutSec)

    fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)
}

class NetError(message: String) : IOException(message)
