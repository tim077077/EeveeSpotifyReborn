package com.slidemill.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import java.util.concurrent.Executors

/** Tapping a "Ready to post" notification lands here: hand the slides to TikTok, then close. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val postId = intent.getStringExtra("post_id")
        if (postId == null) {
            finish()
            return
        }
        Executors.newSingleThreadExecutor().execute {
            val error = runCatching { Slidemill.engine(this).sharePost(postId) }.exceptionOrNull()
            runOnUiThread {
                Toast.makeText(
                    this,
                    error?.let { "Couldn't open TikTok: ${it.message}" } ?: "Caption copied: paste it in TikTok, add a sound and post.",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }
}
