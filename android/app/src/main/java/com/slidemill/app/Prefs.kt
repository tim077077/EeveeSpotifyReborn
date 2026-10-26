package com.slidemill.app

import android.content.Context
import android.content.SharedPreferences

/** App settings: the local API token and whether auto-posting is on. */
object Prefs {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("slidemill", Context.MODE_PRIVATE)
        }
    }

    /** Password for the in-app API server (127.0.0.1 only). */
    var token: String
        get() = prefs.getString("token", "")!!
        set(v) = prefs.edit().putString("token", v).apply()

    var autoPost: Boolean
        get() = prefs.getBoolean("autoPost", false)
        set(v) = prefs.edit().putBoolean("autoPost", v).apply()

    /** Job results waiting to be recorded (survives the app being killed mid-post). */
    fun saveResult(postId: String, json: String) = prefs.edit().putString("result:$postId", json).apply()
    fun removeResult(postId: String) = prefs.edit().remove("result:$postId").apply()
    fun savedResults(): Map<String, String> =
        prefs.all.filterKeys { it.startsWith("result:") }.map { it.key.removePrefix("result:") to it.value.toString() }.toMap()
}
