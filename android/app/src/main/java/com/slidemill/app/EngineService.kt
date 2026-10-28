package com.slidemill.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps Slidemill reachable while the app is closed: the engine's connection to the relay (so
 * Claude's connector works) and the logged-in tiktok.com tab (so drafts can be saved). Nothing runs
 * on a timer: everything happens when Claude calls.
 */
class EngineService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Slidemill.channel(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, Slidemill.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Slidemill")
            .setContentText("Ready for Claude")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
        Slidemill.engine(this)
        TikTokWeb.slots(this).forEach { TikTokWeb.of(it).get(this) } // load every account's tiktok.com in the background
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
