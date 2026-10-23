package com.slidemill.app

import android.content.Context
import android.content.SharedPreferences

/** App settings: where Slidemill runs, its password, and whether auto-posting is on. */
object Prefs {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("slidemill", Context.MODE_PRIVATE)
        }
    }

    var server: String
        get() = prefs.getString("server", "")!!.trimEnd('/')
        set(v) = prefs.edit().putString("server", v.trim().trimEnd('/')).apply()

    var key: String
        get() = prefs.getString("key", "")!!
        set(v) = prefs.edit().putString("key", v.trim()).apply()

    var autoPost: Boolean
        get() = prefs.getBoolean("autoPost", false)
        set(v) = prefs.edit().putBoolean("autoPost", v).apply()

    val configured: Boolean get() = server.startsWith("http") && key.isNotEmpty()

    /** Job results waiting to be reported (survives the app being killed mid-post). */
    fun saveResult(postId: String, json: String) = prefs.edit().putString("result:$postId", json).apply()
    fun removeResult(postId: String) = prefs.edit().remove("result:$postId").apply()
    fun savedResults(): Map<String, String> =
        prefs.all.filterKeys { it.startsWith("result:") }.map { it.key.removePrefix("result:") to it.value.toString() }.toMap()
}
