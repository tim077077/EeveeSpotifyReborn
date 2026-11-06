package com.slidemill.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/**
 * Wake-up push. With the screen off the relay connection dies silently, so when Claude calls, the relay
 * sends a Firebase push; it wakes the phone, which reconnects and answers. Firebase is set up from the
 * google-services.json the user pastes in the app (no file in the build), and the phone's push token
 * goes to the relay in its hello.
 */
object Push {
    const val CONFIG_KEY = "push_config"

    /** The Firebase options in a google-services.json, for this app's package. Throws with a plain reason. */
    fun options(json: String, packageName: String): FirebaseOptions {
        val root = runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("That isn't the google-services.json file's text.") }
        val project = root.optJSONObject("project_info") ?: throw IllegalArgumentException("No project_info: paste the whole google-services.json.")
        val clients = root.optJSONArray("client") ?: throw IllegalArgumentException("No Android app in it: add one in Firebase first.")
        val client = (0 until clients.length()).map { clients.getJSONObject(it) }.firstOrNull {
            it.optJSONObject("client_info")?.optJSONObject("android_client_info")?.optString("package_name") == packageName
        } ?: throw IllegalArgumentException("The Firebase Android app must use the package name $packageName.")
        val apiKey = client.optJSONArray("api_key")?.optJSONObject(0)?.optString("current_key").orEmpty()
        val appId = client.optJSONObject("client_info")?.optString("mobilesdk_app_id").orEmpty()
        if (apiKey.isEmpty() || appId.isEmpty()) throw IllegalArgumentException("The file is missing the app id or API key: download it again from Firebase.")
        return FirebaseOptions.Builder()
            .setApplicationId(appId)
            .setApiKey(apiKey)
            .setProjectId(project.optString("project_id"))
            .setGcmSenderId(project.optString("project_number"))
            .build()
    }

    /** Start Firebase (if set up) and hand the push token to the relay. Safe to call more than once. */
    fun init(context: Context) {
        val app = context.applicationContext
        val json = runCatching { Slidemill.engine(app).store.get(CONFIG_KEY) }.getOrDefault("")
        if (json.isBlank()) return
        runCatching {
            if (FirebaseApp.getApps(app).isEmpty()) FirebaseApp.initializeApp(app, options(json, app.packageName))
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> Slidemill.engine(app).relay.pushToken = token }
        }.onFailure { Slidemill.engine(app).platform.log("push setup failed: ${it.message}") }
    }

    /** Whether the relay can wake this phone (Firebase set up and a token sent). */
    fun ready(context: Context): Boolean = runCatching { Slidemill.engine(context).relay.pushToken.isNotEmpty() }.getOrDefault(false)
}

/** Receives the relay's wake-up push: make sure the engine runs and the relay link is back. */
class WakeService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["t"] != "wake") return
        runCatching { EngineService.start(this) } // a high-priority push may start the foreground service
        Keepalive.check(this)
    }

    override fun onNewToken(token: String) {
        Slidemill.engine(this).relay.pushToken = token
    }
}
