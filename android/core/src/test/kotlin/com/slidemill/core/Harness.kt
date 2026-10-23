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
    val port = e.startServer(0)
    println("HARNESS http://127.0.0.1:$port/?t=${e.token}")
    while (true) {
        e.tick()
        Thread.sleep(2000)
    }
}
