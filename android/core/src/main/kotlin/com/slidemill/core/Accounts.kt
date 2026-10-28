package com.slidemill.core

import java.util.concurrent.ConcurrentHashMap

/**
 * The TikTok accounts logged in on the phone, one [TikTokTab] each (its own WebView profile, so its own
 * cookies). Claude names accounts by TikTok username; the app by slot. Usernames are cached, since asking
 * a tab takes a few seconds.
 */
class Accounts(private val e: Engine) {
    private val names = ConcurrentHashMap<String, String>() // slot -> username

    /** [tab]'s logged-in username, asked fresh and cached; "" when logged out. Keeps the cached one if the page doesn't answer. */
    fun usernameOf(tab: TikTokTab): String {
        val name = runCatching { tab.username() }.getOrElse { return names[tab.id].orEmpty() }
        if (name.isEmpty()) names.remove(tab.id) else names[tab.id] = name
        return name
    }

    fun cached(slot: String): String = names[slot].orEmpty()

    /** Every tab, main first: slot and username (asked fresh when [fresh] or not known yet). */
    fun list(fresh: Boolean = false): List<Obj> = e.tabs.values.sortedBy { if (it.id == TikTokTab.MAIN) "" else it.id }.map { t ->
        obj("slot" to t.id, "username" to if (fresh || cached(t.id).isEmpty()) usernameOf(t) else cached(t.id))
    }

    /** Where Claude's slideshows go when it names no account: the app's setting, else the main tab. */
    fun default(): TikTokTab? =
        e.store.get("default_account").takeIf { it.isNotEmpty() }?.let { runCatching { tab(it) }.getOrNull() } ?: e.tiktok

    /** The tab for [account]: a username (with or without @) or a slot; blank = [default]. */
    fun tab(account: String): TikTokTab {
        val want = account.trim().removePrefix("@").lowercase()
        if (e.tabs.isEmpty()) throw DraftError("The phone's TikTok tab isn't running: open the Slidemill app.")
        if (want.isEmpty()) return default()!!
        e.tabs[want]?.let { return it }
        fun find() = e.tabs.values.firstOrNull { cached(it.id).lowercase() == want }
        find()?.let { return it }
        e.tabs.values.forEach { usernameOf(it) }
        return find() ?: throw DraftError(
            "@$want isn't logged in on the phone (logged in: ${list().map { it.str("username") }.filter { it.isNotEmpty() }.joinToString { "@$it" }.ifEmpty { "none" }}). " +
                "Add it in the app: TikTok tab → + Add account.",
        )
    }

    /** The username [account] resolves to ("" when its tab is logged out). */
    fun username(account: String): String = usernameOf(tab(account))

    /**
     * The slot whose session reads TikTok for research (search, other accounts' posts): the app's
     * "research account" setting, so research can run on a spare account instead of one that posts.
     * "" = the main tab.
     */
    fun researchSlot(): String = e.store.get("research_account").takeIf { it.isNotEmpty() }
        ?.let { runCatching { tab(it).id }.getOrNull() } ?: ""
}
