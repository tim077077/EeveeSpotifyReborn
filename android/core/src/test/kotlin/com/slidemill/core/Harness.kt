package com.slidemill.core

import java.io.File

/**
 * Runs the phone engine on a desktop JVM with a fake AI, for driving the real dashboard in a
 * browser (UI tests): ./gradlew :core:harness. Prints the dashboard URL.
 */
fun main() {
    val ai = FakeAi()
    val platform = JvmPlatform()
    val e = TestKit.engine(ai, platform)
    val shared = mutableListOf<String>()
    e.poster = object : PosterHooks {
        override fun status() = obj("available" to true, "running" to true, "auto_post" to true, "logged_in" to true, "username" to "tim", "status" to "@tim · queue empty")
        override fun share(postId: String, slides: List<File>, caption: String) { shared += postId }
    }
    seedLibrary(e, ai)
    val port = e.startServer(0)
    println("HARNESS http://127.0.0.1:$port/?t=${e.token}")
    while (true) {
        e.tick()
        Thread.sleep(2000)
    }
}

/** Twelve library slideshows with known views, and a fake AI that half-agrees with them (for the score test). */
private fun seedLibrary(e: Engine, ai: FakeAi) {
    File(e.mediaDir, "library").mkdirs()
    File(e.mediaDir, "library/h.jpg").writeBytes(TestKit.jpeg(300, 400))
    val hooks = listOf(
        "things nobody tells you about sleep", "my 5am routine", "3 habits that fixed my skin", "stop doing this before bed",
        "what I eat in a day", "the morning walk changed everything", "rating my night routine", "why you're always tired",
        "small habits, big glow up", "I tried mouth taping", "how I fall asleep in 5 minutes", "sleep tips that actually work",
    )
    e.db.tx {
        val prof = insert("library_profiles", obj("username" to "sleepcoach", "niche" to "Health"))
        hooks.forEachIndexed { i, h ->
            insert("library_posts", obj(
                "id" to "h$i", "profile_id" to prof.str("id"), "is_slideshow" to true, "views" to 3000L * (i + 1) * (i + 1),
                "url" to "https://www.tiktok.com/@sleepcoach/photo/$i", "images" to arr("library/h.jpg", "library/h.jpg", "library/h.jpg"),
                "slide_texts" to arr(h, "one", "two"),
            ))
        }
    }
    val noise = listOf(14, -12, 19, 0, -16, 14, -13, 8, -10, 5, 2, -11)
    ai.scoreFor = { user ->
        val hook = user.substringAfter("1. [hook] ").substringBefore("\n")
        val i = hooks.indexOf(hook)
        if (i < 0) 64 else 40 + i * 4 + noise[i]
    }
    // The blind ranker mostly agrees with real results (two neighbours swapped).
    ai.rankBy = { text ->
        val i = hooks.indexOf(text.substringBefore(" / "))
        when (i) { 3 -> 4.0; 4 -> 3.0; -1 -> 6.5; else -> i.toDouble() }
    }
}
